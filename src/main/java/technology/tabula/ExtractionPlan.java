package technology.tabula;

import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.cli.ParseException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

/**
 * A reusable, page-independent description of the regions to extract from a
 * document and the extraction mode to use for each one.
 *
 * <p>The plan is read from JSON. Example:</p>
 *
 * <pre>
 * {
 *   "minimum_coverage": 0.5,
 *   "regions": [
 *     { "name": "settlement-summary", "mode": "lattice",
 *       "top": 60, "left": 40, "bottom": 300, "right": 560 },
 *     { "name": "line-items", "mode": "stream",
 *       "area": "%0,0,100,50" }
 *   ]
 * }
 * </pre>
 *
 * <p>Region bounds use the same coordinate system as the {@code --area}
 * command line option: {@code top,left,bottom,right} in points relative to the
 * top left corner of the page, optionally prefixed with {@code %} to express
 * them as percentages of the page height/width. A region may spell its bounds
 * either with the four numeric properties {@code top}, {@code left},
 * {@code bottom} and {@code right}, or as a single {@code area} property
 * holding a string ({@code "top,left,bottom,right"}, optional {@code %} prefix)
 * or an array of four numbers.</p>
 *
 * <p>Region names must be unique. A plan-level {@code default_mode} may be
 * given; otherwise every region must declare its own {@code mode}, which is
 * either {@code "lattice"} (also accepted: {@code "spreadsheet"}) or
 * {@code "stream"} (also accepted: {@code "basic"}).</p>
 *
 * <p>When regions overlap, a character is assigned to at most one region. For
 * every candidate region the fraction of the character's area that the region
 * covers is computed. Regions that cover less than {@code minimum_coverage}
 * (0.5 by default) are discarded; among the remaining ones the largest
 * coverage wins. Ties are broken in favour of the region with the smaller
 * area, and finally in favour of the region declared first.</p>
 *
 * <p>A lattice region that yields no cells at all (for example, because it
 * contains no ruling lines) falls back to stream so its text is not lost; the
 * resulting table then reports {@code "stream"} as its actual mode.</p>
 */
public class ExtractionPlan {

    /** Default fraction of a character's area a region must cover to claim it. */
    public static final float DEFAULT_MINIMUM_COVERAGE = 0.5f;

    private static final float DEFAULT_MIN_CHAR_LENGTH = 7f;

    /** Tolerance used when comparing coverage ratios and region areas. */
    private static final double COMPARISON_EPSILON = 1e-6d;

    private final List<Region> regions;
    private final Mode defaultMode;
    private final float minimumCoverage;

    private ExtractionPlan(List<Region> regions, Mode defaultMode, float minimumCoverage) {
        this.regions = regions;
        this.defaultMode = defaultMode;
        this.minimumCoverage = minimumCoverage;
    }

    /**
     * The extraction mode of a region. The label is the value used both in the
     * plan file and in the {@code extraction_method} field of the output.
     */
    public enum Mode {
        LATTICE,
        STREAM;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Mode fromLabel(String label) {
            if (label != null) {
                String normalized = label.trim().toLowerCase(Locale.ROOT);
                for (Mode mode : values()) {
                    if (mode.label().equals(normalized)) {
                        return mode;
                    }
                }
                // Accept the naming used by the extractor classes themselves.
                if ("spreadsheet".equals(normalized)) {
                    return LATTICE;
                }
                if ("basic".equals(normalized)) {
                    return STREAM;
                }
            }
            return null;
        }
    }

    /** A named rectangular region of a page and the mode used to extract it. */
    public static final class Region {
        private final String name;
        private final Bounds bounds;
        private final Mode mode;

        private Region(String name, Bounds bounds, Mode mode) {
            this.name = name;
            this.bounds = bounds;
            this.mode = mode;
        }

        public String getName() {
            return name;
        }

        public Mode getMode() {
            return mode;
        }

        /** Whether the configured bounds are percentages of the page size. */
        public boolean isPercent() {
            return bounds.percent;
        }

        public float getTop() {
            return bounds.top;
        }

        public float getLeft() {
            return bounds.left;
        }

        public float getBottom() {
            return bounds.bottom;
        }

        public float getRight() {
            return bounds.right;
        }

        /** Resolves the (possibly relative) bounds against a concrete page. */
        public Rectangle getBounds(Page page) {
            return bounds.toRectangle(page);
        }
    }

    // ------------------------------------------------------------------
    // Parsing

    public static ExtractionPlan fromFile(File file) throws ParseException {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return fromReader(reader);
        } catch (IOException e) {
            throw new ParseException("Cannot read extraction plan " + file + ": " + e.getMessage());
        }
    }

    public static ExtractionPlan fromJson(String json) throws ParseException {
        return fromReader(new StringReader(json));
    }

    public static ExtractionPlan fromReader(Reader reader) throws ParseException {
        String json;
        try {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            json = sb.toString();
        } catch (IOException e) {
            throw new ParseException("Cannot read extraction plan: " + e.getMessage());
        }

        // Tolerate a UTF-8 byte order mark.
        if (json.startsWith("\ufeff")) {
            json = json.substring(1);
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new ParseException("Invalid extraction plan JSON: " + e.getMessage());
        }

        if (root == null || !root.isJsonObject()) {
            throw new ParseException("Extraction plan must be a JSON object");
        }
        return fromJsonObject(root.getAsJsonObject());
    }

    private static ExtractionPlan fromJsonObject(JsonObject root) throws ParseException {
        float minimumCoverage = DEFAULT_MINIMUM_COVERAGE;
        if (root.has("minimum_coverage")) {
            Float value = asFloat(root.get("minimum_coverage"));
            if (value == null) {
                throw new ParseException("minimum_coverage must be a number");
            }
            if (value < 0f || value > 1f) {
                throw new ParseException("minimum_coverage must be between 0 and 1");
            }
            minimumCoverage = value;
        }

        Mode defaultMode = null;
        if (root.has("default_mode")) {
            String label = asString(root.get("default_mode"));
            defaultMode = Mode.fromLabel(label);
            if (defaultMode == null) {
                throw new ParseException("default_mode must be \"lattice\" or \"stream\"");
            }
        }

        JsonElement regionsElement = root.get("regions");
        if (regionsElement == null || !regionsElement.isJsonArray()) {
            throw new ParseException("Extraction plan must contain a \"regions\" array");
        }

        JsonArray regionsArray = regionsElement.getAsJsonArray();
        if (regionsArray.size() == 0) {
            throw new ParseException("Extraction plan must contain at least one region");
        }

        List<Region> regions = new ArrayList<>(regionsArray.size());
        Set<String> names = new HashSet<>();

        for (int i = 0; i < regionsArray.size(); i++) {
            JsonElement element = regionsArray.get(i);
            if (!element.isJsonObject()) {
                throw new ParseException("regions[" + i + "] must be an object");
            }
            JsonObject regionObject = element.getAsJsonObject();

            String name = asString(regionObject.get("name"));
            if (name == null || name.trim().isEmpty()) {
                throw new ParseException("regions[" + i + "] must have a non-empty name");
            }
            if (!names.add(name)) {
                throw new ParseException("Duplicate region name: " + name);
            }

            Mode mode = defaultMode;
            if (regionObject.has("mode")) {
                mode = Mode.fromLabel(asString(regionObject.get("mode")));
                if (mode == null) {
                    throw new ParseException("regions[" + i + "].mode must be \"lattice\" or \"stream\"");
                }
            }
            if (mode == null) {
                throw new ParseException("regions[" + i + "] has no mode and the plan has no default_mode");
            }

            Bounds bounds;
            if (regionObject.has("area")) {
                bounds = parseAreaElement(regionObject.get("area"), i);
            } else {
                bounds = parseBoundsFields(regionObject, i);
            }

            regions.add(new Region(name, bounds, mode));
        }

        return new ExtractionPlan(Collections.unmodifiableList(regions), defaultMode, minimumCoverage);
    }

    private static Bounds parseAreaElement(JsonElement element, int index) throws ParseException {
        if (element == null) {
            throw new ParseException("regions[" + index + "].area must not be null");
        }

        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString().trim();
            boolean percent = value.startsWith("%");
            if (percent) {
                value = value.substring(1);
            }
            String[] parts = value.split(",");
            if (parts.length != 4) {
                throw new ParseException("regions[" + index + "].area must have four values: top,left,bottom,right");
            }
            float[] values = new float[4];
            for (int i = 0; i < 4; i++) {
                try {
                    values[i] = Float.parseFloat(parts[i].trim());
                } catch (NumberFormatException e) {
                    throw new ParseException("regions[" + index + "].area contains a non-numeric value");
                }
            }
            return validated(new Bounds(percent, values[0], values[1], values[2], values[3]), index);
        }

        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.size() != 4) {
                throw new ParseException("regions[" + index + "].area must contain exactly four numbers");
            }
            float[] values = new float[4];
            for (int i = 0; i < 4; i++) {
                Float value = asFloat(array.get(i));
                if (value == null) {
                    throw new ParseException("regions[" + index + "].area must contain only numbers");
                }
                values[i] = value;
            }
            return validated(new Bounds(false, values[0], values[1], values[2], values[3]), index);
        }

        throw new ParseException("regions[" + index + "].area must be a string or an array of four numbers");
    }

    private static Bounds parseBoundsFields(JsonObject regionObject, int index) throws ParseException {
        String[] fields = { "top", "left", "bottom", "right" };
        float[] values = new float[4];
        for (int i = 0; i < fields.length; i++) {
            Float value = asFloat(regionObject.get(fields[i]));
            if (value == null) {
                throw new ParseException(
                        "regions[" + index + "] must define top,left,bottom,right or an area");
            }
            values[i] = value;
        }
        return validated(new Bounds(false, values[0], values[1], values[2], values[3]), index);
    }

    private static Bounds validated(Bounds bounds, int index) throws ParseException {
        if (!isFinite(bounds.top) || !isFinite(bounds.left)
                || !isFinite(bounds.bottom) || !isFinite(bounds.right)) {
            throw new ParseException("regions[" + index + "] has non-finite bounds");
        }
        if (bounds.bottom <= bounds.top) {
            throw new ParseException("regions[" + index + "].bottom must be greater than top");
        }
        if (bounds.right <= bounds.left) {
            throw new ParseException("regions[" + index + "].right must be greater than left");
        }
        return bounds;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static Float asFloat(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            float value = element.getAsFloat();
            return isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String asString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    // ------------------------------------------------------------------
    // Accessors

    public List<Region> getRegions() {
        return regions;
    }

    public Mode getDefaultMode() {
        return defaultMode;
    }

    public float getMinimumCoverage() {
        return minimumCoverage;
    }

    // ------------------------------------------------------------------
    // Text assignment

    /**
     * Assigns every character of {@code page} to at most one region.
     *
     * @return one list per region, in declaration order, holding the characters
     *         assigned to that region
     */
    public List<List<TextElement>> assignTextElements(Page page) {
        List<Rectangle> bounds = new ArrayList<>(regions.size());
        for (Region region : regions) {
            bounds.add(region.getBounds(page));
        }
        return assignTextElements(page, bounds);
    }

    private List<List<TextElement>> assignTextElements(Page page, List<Rectangle> bounds) {
        List<List<TextElement>> assigned = new ArrayList<>(regions.size());
        for (int i = 0; i < regions.size(); i++) {
            assigned.add(new ArrayList<TextElement>());
        }

        for (TextElement textElement : page.getText()) {
            int chosen = chooseRegion(textElement, bounds);
            if (chosen >= 0) {
                assigned.get(chosen).add(textElement);
            }
        }
        return assigned;
    }

    /**
     * Applies the arbitration rules to a single character.
     *
     * @return the index of the winning region, or -1 if no region covers
     *         enough of the character
     */
    private int chooseRegion(Rectangle textElement, List<Rectangle> bounds) {
        // First pass: the best coverage that clears the threshold. A region
        // that does not touch the character can never claim it, even when the
        // threshold is zero.
        float bestCoverage = Float.NEGATIVE_INFINITY;
        for (Rectangle region : bounds) {
            float coverage = coverage(textElement, region);
            if (coverage <= 0f || coverage + COMPARISON_EPSILON < minimumCoverage) {
                continue;
            }
            bestCoverage = Math.max(bestCoverage, coverage);
        }

        if (bestCoverage == Float.NEGATIVE_INFINITY) {
            return -1;
        }

        // Second pass: among regions tied on coverage, the smallest area wins;
        // the earliest declaration breaks any remaining tie.
        int best = -1;
        float bestArea = Float.POSITIVE_INFINITY;
        for (int i = 0; i < bounds.size(); i++) {
            Rectangle region = bounds.get(i);
            float coverage = coverage(textElement, region);
            if (coverage <= 0f || coverage + COMPARISON_EPSILON < bestCoverage) {
                continue;
            }
            float area = region.getArea();
            if (best < 0 || area < bestArea - COMPARISON_EPSILON) {
                best = i;
                bestArea = area;
            }
        }

        return best;
    }

    private static float coverage(Rectangle element, Rectangle region) {
        double elementWidth = element.getWidth();
        double elementHeight = element.getHeight();
        if (elementWidth <= 0d || elementHeight <= 0d) {
            return 0f;
        }

        double intersectionWidth = Math.max(0d,
                Math.min(element.getRight(), region.getRight()) - Math.max(element.getLeft(), region.getLeft()));
        double intersectionHeight = Math.max(0d,
                Math.min(element.getBottom(), region.getBottom()) - Math.max(element.getTop(), region.getTop()));
        double intersectionArea = intersectionWidth * intersectionHeight;

        return (float) (intersectionArea / (elementWidth * elementHeight));
    }

    // ------------------------------------------------------------------
    // Extraction

    /**
     * Extracts the regions of {@code page} with their own mode, in declaration
     * order. Each returned table is tagged with the name of the region it came
     * from and reports the mode that was actually used.
     */
    public List<Table> extract(Page page) {
        List<Rectangle> bounds = new ArrayList<>(regions.size());
        for (Region region : regions) {
            bounds.add(region.getBounds(page));
        }

        List<List<TextElement>> assigned = assignTextElements(page, bounds);
        // Snapshot the page's (snapped/collapsed) rulings once so that each
        // region works on its own copies.
        List<Ruling> pageRulings = new ArrayList<>();
        for (Ruling ruling : page.getRulings()) {
            pageRulings.add(new Ruling(ruling.getP1(), ruling.getP2()));
        }

        List<Table> tables = new ArrayList<>();

        for (int i = 0; i < regions.size(); i++) {
            Region region = regions.get(i);
            List<TextElement> textElements = assigned.get(i);
            if (textElements.isEmpty()) {
                continue;
            }

            Page regionPage = buildRegionPage(page, bounds.get(i), textElements, pageRulings);
            List<Table> regionTables;
            if (region.getMode() == Mode.LATTICE) {
                regionTables = new SpreadsheetExtractionAlgorithm().extract(regionPage);
                if (!hasUsableTable(regionTables)) {
                    // No complete cells could be formed inside the region (for
                    // example, it declares lattice but has no ruling lines).
                    // Fall back to stream so the region's text is not dropped;
                    // the table then reports "stream" as its actual mode.
                    regionTables = new BasicExtractionAlgorithm().extract(regionPage);
                }
            } else {
                regionTables = new BasicExtractionAlgorithm().extract(regionPage);
            }

            for (Table table : regionTables) {
                if (table.getRowCount() == 0 && table.getColCount() == 0) {
                    continue;
                }
                table.setRegionName(region.getName());
                tables.add(table);
            }
        }

        return tables;
    }

    private static boolean hasUsableTable(List<Table> tables) {
        for (Table table : tables) {
            if (table.getRowCount() > 0 || table.getColCount() > 0) {
                return true;
            }
        }
        return false;
    }

    private static Page buildRegionPage(Page page, Rectangle area, List<TextElement> textElements,
                                        List<Ruling> pageRulings) {
        RectangleSpatialIndex<TextElement> index = new RectangleSpatialIndex<>();
        float minCharWidth = (float) textElements.get(0).getWidth();
        float minCharHeight = (float) textElements.get(0).getHeight();
        for (TextElement textElement : textElements) {
            index.add(textElement);
            minCharWidth = Math.min(minCharWidth, (float) textElement.getWidth());
            minCharHeight = Math.min(minCharHeight, (float) textElement.getHeight());
        }
        if (minCharWidth <= 0f) {
            minCharWidth = DEFAULT_MIN_CHAR_LENGTH;
        }
        if (minCharHeight <= 0f) {
            minCharHeight = DEFAULT_MIN_CHAR_LENGTH;
        }

        // Copy the rulings so that snapping points for one region cannot mutate
        // the lines shared with the rest of the page.
        List<Ruling> rulings = new ArrayList<>();
        for (Ruling ruling : Ruling.cropRulingsToArea(pageRulings, area)) {
            rulings.add(new Ruling(ruling.getP1(), ruling.getP2()));
        }

        Page regionPage = Page.Builder.newInstance()
                .withPageDims(PageDims.of(area.getTop(), area.getLeft(), (float) area.getWidth(), (float) area.getHeight()))
                .withRotation(page.getRotation() == null ? 0 : page.getRotation())
                .withNumber(page.getPageNumber())
                .withPdPage(page.getPDPage())
                .withPdDocument(page.getPDDoc())
                .withTextElements(textElements)
                .withRulings(rulings)
                .withMinCharWidth(minCharWidth)
                .withMinCharHeight(minCharHeight)
                .withIndex(index)
                .build();

        addBorderRulings(regionPage, area);
        return regionPage;
    }

    private static void addBorderRulings(Page page, Rectangle area) {
        Point2D.Double leftTop = new Point2D.Double(area.getLeft(), area.getTop());
        Point2D.Double rightTop = new Point2D.Double(area.getRight(), area.getTop());
        Point2D.Double rightBottom = new Point2D.Double(area.getRight(), area.getBottom());
        Point2D.Double leftBottom = new Point2D.Double(area.getLeft(), area.getBottom());

        page.addRuling(new Ruling(leftTop, rightTop));
        page.addRuling(new Ruling(rightTop, rightBottom));
        page.addRuling(new Ruling(rightBottom, leftBottom));
        page.addRuling(new Ruling(leftBottom, leftTop));
    }

    // ------------------------------------------------------------------

    private static final class Bounds {
        private final boolean percent;
        private final float top;
        private final float left;
        private final float bottom;
        private final float right;

        private Bounds(boolean percent, float top, float left, float bottom, float right) {
            this.percent = percent;
            this.top = top;
            this.left = left;
            this.bottom = bottom;
            this.right = right;
        }

        private Rectangle toRectangle(Page page) {
            if (!percent) {
                return new Rectangle(top, left, right - left, bottom - top);
            }

            float pageWidth = (float) page.getWidth();
            float pageHeight = (float) page.getHeight();
            return new Rectangle(top / 100f * pageHeight,
                    left / 100f * pageWidth,
                    (right - left) / 100f * pageWidth,
                    (bottom - top) / 100f * pageHeight);
        }
    }
}
