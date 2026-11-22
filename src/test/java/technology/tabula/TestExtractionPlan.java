package technology.tabula;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.ParseException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import technology.tabula.ExtractionPlan.Mode;

public class TestExtractionPlan {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // ------------------------------------------------------------------
    // Parsing

    @Test
    public void parsesRegionsInDeclarationOrder() throws ParseException {
        ExtractionPlan plan = ExtractionPlan.fromJson(
                "{\"regions\": ["
                        + "{\"name\": \"summary\", \"mode\": \"lattice\", \"top\": 10, \"left\": 20, \"bottom\": 30, \"right\": 40},"
                        + "{\"name\": \"details\", \"mode\": \"stream\", \"top\": 50, \"left\": 60, \"bottom\": 70, \"right\": 80}"
                        + "]}");

        assertEquals(2, plan.getRegions().size());

        ExtractionPlan.Region summary = plan.getRegions().get(0);
        assertEquals("summary", summary.getName());
        assertEquals(Mode.LATTICE, summary.getMode());
        assertEquals(10f, summary.getTop(), 0.001f);
        assertEquals(20f, summary.getLeft(), 0.001f);
        assertEquals(30f, summary.getBottom(), 0.001f);
        assertEquals(40f, summary.getRight(), 0.001f);

        ExtractionPlan.Region details = plan.getRegions().get(1);
        assertEquals("details", details.getName());
        assertEquals(Mode.STREAM, details.getMode());

        assertEquals(ExtractionPlan.DEFAULT_MINIMUM_COVERAGE, plan.getMinimumCoverage(), 0.0001f);
    }

    @Test
    public void parsesAreaStringAndArrayAndDefaultMode() throws ParseException {
        ExtractionPlan plan = ExtractionPlan.fromJson(
                "{\"default_mode\": \"stream\", \"regions\": ["
                        + "{\"name\": \"a\", \"area\": \"10,20,30,40\"},"
                        + "{\"name\": \"b\", \"area\": [50, 60, 70, 80]},"
                        + "{\"name\": \"c\", \"mode\": \"lattice\", \"area\": \"1,2,3,4\"}"
                        + "]}");

        assertEquals(3, plan.getRegions().size());
        assertEquals(Mode.STREAM, plan.getRegions().get(0).getMode());
        assertEquals(Mode.STREAM, plan.getRegions().get(1).getMode());
        assertEquals(Mode.LATTICE, plan.getRegions().get(2).getMode());
        assertEquals(50f, plan.getRegions().get(1).getTop(), 0.001f);
    }

    @Test
    public void parsesPercentBounds() throws ParseException {
        ExtractionPlan plan = ExtractionPlan.fromJson(
                "{\"regions\": [{\"name\": \"top-half\", \"mode\": \"stream\", \"area\": \"%0,0,50,100\"}]}");

        ExtractionPlan.Region region = plan.getRegions().get(0);
        assertTrue(region.isPercent());

        Page page = syntheticPage(1000f, 500f, new ArrayList<TextElement>());
        Rectangle bounds = region.getBounds(page);
        assertEquals(0f, bounds.getTop(), 0.001f);
        assertEquals(0f, bounds.getLeft(), 0.001f);
        assertEquals(500f, bounds.getBottom(), 0.001f);
        assertEquals(500f, bounds.getRight(), 0.001f);
    }

    @Test
    public void rejectsDuplicateNames() {
        assertParseError("{\"regions\": ["
                + "{\"name\": \"x\", \"mode\": \"stream\", \"area\": \"0,0,10,10\"},"
                + "{\"name\": \"x\", \"mode\": \"stream\", \"area\": \"0,0,10,10\"}]}");
    }

    @Test
    public void rejectsMissingMode() {
        assertParseError("{\"regions\": [{\"name\": \"x\", \"area\": \"0,0,10,10\"}]}");
    }

    @Test
    public void rejectsInvalidBounds() {
        assertParseError("{\"regions\": [{\"name\": \"x\", \"mode\": \"stream\", \"area\": \"10,0,0,10\"}]}");
    }

    @Test
    public void rejectsEmptyRegionList() {
        assertParseError("{\"regions\": []}");
    }

    @Test
    public void rejectsInvalidMinimumCoverage() {
        assertParseError("{\"minimum_coverage\": 2, \"regions\": "
                + "[{\"name\": \"x\", \"mode\": \"stream\", \"area\": \"0,0,10,10\"}]}");
    }

    @Test
    public void loadsPlanFromFile() throws Exception {
        File file = folder.newFile("plan.json");
        Files.write(file.toPath(),
                "{\"regions\": [{\"name\": \"r\", \"mode\": \"stream\", \"area\": \"0,0,10,10\"}]}"
                        .getBytes(StandardCharsets.UTF_8));

        ExtractionPlan plan = ExtractionPlan.fromFile(file);
        assertEquals(1, plan.getRegions().size());
        assertEquals("r", plan.getRegions().get(0).getName());
    }

    // ------------------------------------------------------------------
    // Character arbitration

    @Test
    public void assignsFullyCoveredCharacterToSmallestRegion() {
        // Both regions contain the character completely; the smaller one wins.
        ExtractionPlan plan = plan(0.5f,
                region("big", 0, 0, 100, 100),
                region("small", 0, 0, 10, 10));

        Page page = syntheticPage(200f, 200f, chars(charAt("a", 2f, 2f, 4f, 4f)));
        List<List<TextElement>> assigned = plan.assignTextElements(page);

        assertEquals(0, assigned.get(0).size());
        assertEquals(1, assigned.get(1).size());
    }

    @Test
    public void choosesRegionWithLargestCoverage() {
        // The character straddles both regions but region "right" covers more.
        ExtractionPlan plan = plan(0.5f,
                region("left", 0, 0, 100, 100),
                region("right", 0, 50, 100, 200));

        // The character spans x = 60..120. "left" covers 40 of those 60 units
        // (66%), "right" covers all 60 (100%), so "right" wins even though it
        // is the larger region.
        Page page = syntheticPage(200f, 200f, chars(charAt("a", 10f, 60f, 60f, 10f)));
        List<List<TextElement>> assigned = plan.assignTextElements(page);

        assertEquals(0, assigned.get(0).size());
        assertEquals(1, assigned.get(1).size());
    }

    @Test
    public void dropsCharacterBelowMinimumCoverage() {
        // The character spans x = 60..100; the split is at x = 76, so "left"
        // covers 40% of it and "right" covers 60%.
        ExtractionPlan plan = plan(0.5f,
                region("left", 0, 0, 100, 76),
                region("right", 0, 76, 100, 200));

        Page page = syntheticPage(200f, 200f, chars(charAt("a", 10f, 60f, 40f, 10f)));
        List<List<TextElement>> assigned = plan.assignTextElements(page);
        assertEquals(0, assigned.get(0).size());
        assertEquals(1, assigned.get(1).size());

        // Now require 80% coverage: neither region qualifies, character is dropped.
        ExtractionPlan strict = plan(0.8f,
                region("left", 0, 0, 100, 76),
                region("right", 0, 76, 100, 200));
        List<List<TextElement>> strictAssigned = strict.assignTextElements(page);
        assertEquals(0, strictAssigned.get(0).size());
        assertEquals(0, strictAssigned.get(1).size());
    }

    @Test
    public void meetsThresholdExactly() {
        ExtractionPlan plan = plan(0.5f,
                region("left", 0, 0, 100, 100));

        // Exactly half of the character (x = 90..110) lies inside the region.
        Page page = syntheticPage(200f, 200f, chars(charAt("a", 10f, 90f, 20f, 10f)));
        List<List<TextElement>> assigned = plan.assignTextElements(page);
        assertEquals(1, assigned.get(0).size());
    }

    @Test
    public void tiesBreakOnDeclarationOrder() {
        // Identical regions: the first declared one wins.
        ExtractionPlan plan = plan(0.5f,
                region("first", 0, 0, 100, 100),
                region("second", 0, 0, 100, 100));

        Page page = syntheticPage(200f, 200f, chars(charAt("a", 10f, 10f, 10f, 10f)));
        List<List<TextElement>> assigned = plan.assignTextElements(page);

        assertEquals(1, assigned.get(0).size());
        assertEquals(0, assigned.get(1).size());
    }

    @Test
    public void assignsEachCharacterToAtMostOneRegion() {
        ExtractionPlan plan = plan(0.1f,
                region("a", 0, 0, 100, 100),
                region("b", 0, 0, 100, 100),
                region("c", 0, 50, 100, 200));

        List<TextElement> text = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            text.add(charAt("x", 10f, i * 10f, 10f, 10f));
        }

        List<List<TextElement>> assigned = plan.assignTextElements(syntheticPage(200f, 200f, text));
        int total = 0;
        for (List<TextElement> list : assigned) {
            total += list.size();
        }
        assertEquals(text.size(), total);
    }

    // ------------------------------------------------------------------
    // Extraction

    @Test
    public void extractsStreamRegionWithItsOwnText() throws ParseException {
        ExtractionPlan plan = plan(0.5f,
                region("left", 0, 0, 100, 100),
                region("right", 0, 100, 100, 200));

        List<TextElement> text = new ArrayList<>();
        text.add(charAt("A", 10f, 10f, 10f, 10f));
        text.add(charAt("B", 10f, 150f, 10f, 10f));

        List<Table> tables = plan.extract(syntheticPage(200f, 200f, text));
        assertEquals(2, tables.size());

        assertEquals("left", tables.get(0).getRegionName());
        assertEquals("stream", tables.get(0).getExtractionMethod());
        assertEquals("A", tables.get(0).getCell(0, 0).getText());

        assertEquals("right", tables.get(1).getRegionName());
        assertEquals("stream", tables.get(1).getExtractionMethod());
        assertEquals("B", tables.get(1).getCell(0, 0).getText());
    }

    @Test
    public void skipsRegionsWithoutText() throws ParseException {
        ExtractionPlan plan = plan(0.5f,
                region("empty", 0, 0, 50, 50),
                region("filled", 0, 100, 100, 200));

        List<Table> tables = plan.extract(
                syntheticPage(200f, 200f, chars(charAt("Z", 10f, 150f, 10f, 10f))));

        assertEquals(1, tables.size());
        assertEquals("filled", tables.get(0).getRegionName());
    }

    @Test
    public void extractsLatticeRegionUsingItsInteriorRulings() throws Exception {
        File planFile = folder.newFile("lattice.json");
        Files.write(planFile.toPath(), ("{\"regions\": ["
                + "{\"name\": \"framed\", \"mode\": \"lattice\", \"area\": \"111,67,285,470\"}"
                + "]}").getBytes(StandardCharsets.UTF_8));

        Page page = UtilsForTesting.getPage(
                "src/test/resources/technology/tabula/twotables.pdf", 1);
        List<Table> tables = ExtractionPlan.fromFile(planFile).extract(page);

        assertEquals(1, tables.size());
        Table table = tables.get(0);
        assertEquals("framed", table.getRegionName());
        assertEquals("lattice", table.getExtractionMethod());
        assertEquals(6, table.getColCount());
        assertEquals("株主資本", table.getCell(0, 1).getText());
    }

    @Test
    public void latticeRegionWithoutCellsReportsActualStreamMode() throws Exception {
        // The plan asks for lattice, but this region has no ruling lines, so
        // the text is extracted with stream and the table reports the mode that
        // was actually used.
        File planFile = folder.newFile("nolines.json");
        Files.write(planFile.toPath(), ("{\"regions\": ["
                + "{\"name\": \"plain\", \"mode\": \"lattice\", \"area\": \"0,0,451,212\"}"
                + "]}").getBytes(StandardCharsets.UTF_8));

        String json = runCommandLine(
                "src/test/resources/technology/tabula/MultiColumn.pdf",
                "-p", "1", "--plan", planFile.getAbsolutePath(), "-f", "JSON");

        JsonArray tables = JsonParser.parseString(json).getAsJsonArray();
        assertEquals(1, tables.size());
        JsonObject table = tables.get(0).getAsJsonObject();
        assertEquals("plain", table.get("region_name").getAsString());
        assertEquals("stream", table.get("extraction_method").getAsString());
        assertTrue(table.get("data").getAsJsonArray().size() > 0);
    }

    @Test
    public void outputIsOrderedByPageThenDeclarationOrder() throws Exception {
        File planFile = folder.newFile("twopages.json");
        Files.write(planFile.toPath(), ("{\"regions\": ["
                + "{\"name\": \"upper\", \"mode\": \"stream\", \"area\": \"%0,0,50,100\"},"
                + "{\"name\": \"lower\", \"mode\": \"stream\", \"area\": \"%50,0,100,100\"}"
                + "]}").getBytes(StandardCharsets.UTF_8));

        String json = runCommandLine(
                "src/test/resources/technology/tabula/eu-002.pdf",
                "-p", "1-2", "--plan", planFile.getAbsolutePath(), "-f", "JSON");

        JsonArray tables = JsonParser.parseString(json).getAsJsonArray();
        assertTrue(tables.size() >= 2);

        List<String> order = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            JsonObject table = tables.get(i).getAsJsonObject();
            order.add(table.get("page_number").getAsInt()
                    + ":" + table.get("region_name").getAsString());
        }

        List<String> sorted = new ArrayList<>(order);
        java.util.Collections.sort(sorted, new java.util.Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int leftPage = Integer.parseInt(left.substring(0, left.indexOf(':')));
                int rightPage = Integer.parseInt(right.substring(0, right.indexOf(':')));
                if (leftPage != rightPage) {
                    return Integer.compare(leftPage, rightPage);
                }
                String leftRegion = left.substring(left.indexOf(':') + 1);
                String rightRegion = right.substring(right.indexOf(':') + 1);
                int leftIndex = indexOfRegion(leftRegion);
                int rightIndex = indexOfRegion(rightRegion);
                return Integer.compare(leftIndex, rightIndex);
            }

            private int indexOfRegion(String name) {
                return "upper".equals(name) ? 0 : 1;
            }
        });
        assertEquals(sorted, order);
    }

    // ------------------------------------------------------------------
    // Command line integration

    @Test
    public void commandLinePlanProducesNamedTablesInOrder() throws Exception {
        File planFile = folder.newFile("plan.json");
        Files.write(planFile.toPath(), ("{\"regions\": ["
                + "{\"name\": \"settlement-summary\", \"mode\": \"lattice\", \"area\": \"60,40,430,560\"},"
                + "{\"name\": \"notes\", \"mode\": \"stream\", \"area\": \"600,40,841,560\"}"
                + "]}").getBytes(StandardCharsets.UTF_8));

        String json = runCommandLine(
                "src/test/resources/technology/tabula/twotables.pdf",
                "-p", "1", "--plan", planFile.getAbsolutePath(), "-f", "JSON");

        JsonArray tables = JsonParser.parseString(json).getAsJsonArray();
        assertTrue(tables.size() >= 2);

        // Every table carries the region it came from and its actual mode.
        for (int i = 0; i < tables.size(); i++) {
            JsonObject table = tables.get(i).getAsJsonObject();
            assertTrue(table.has("region_name"));
            assertEquals(1, table.get("page_number").getAsInt());
        }

        JsonObject first = tables.get(0).getAsJsonObject();
        assertEquals("settlement-summary", first.get("region_name").getAsString());
        assertEquals("lattice", first.get("extraction_method").getAsString());

        JsonObject last = tables.get(tables.size() - 1).getAsJsonObject();
        assertEquals("notes", last.get("region_name").getAsString());
        assertEquals("stream", last.get("extraction_method").getAsString());
    }

    @Test
    public void commandLineWithoutPlanOmitsRegionName() throws Exception {
        String json = runCommandLine(
                "src/test/resources/technology/tabula/spanning_cells.pdf",
                "-p", "1", "-a", "150.56,58.9,654.7,536.12", "-f", "JSON");

        JsonArray tables = JsonParser.parseString(json).getAsJsonArray();
        assertTrue(tables.size() > 0);
        for (int i = 0; i < tables.size(); i++) {
            JsonObject table = tables.get(i).getAsJsonObject();
            assertTrue(!table.has("region_name"));
        }
    }

    @Test
    public void commandLineWithoutPlanOutputIsUnchanged() throws Exception {
        String expectedJson = UtilsForTesting.loadJson(
                "src/test/resources/technology/tabula/json/AnimalSounds1.json");
        String actual = runCommandLine(
                "src/test/resources/technology/tabula/AnimalSounds1.pdf",
                "-p", "1", "-c", "57,136,197,296,314,391,457,553",
                "-a", "%0,0,100,50", "-a", "%0,50,100,100",
                "-r", "-f", "JSON");

        assertEquals(expectedJson, actual);
    }

    @Test
    public void overlappingRegionsDoNotDuplicateText() throws Exception {
        // The stream region also covers the framed table; arbitration should
        // hand each character to exactly one of the two.
        File planFile = folder.newFile("overlap.json");
        Files.write(planFile.toPath(), ("{\"minimum_coverage\": 0.5, \"regions\": ["
                + "{\"name\": \"framed\", \"mode\": \"lattice\", \"area\": \"111,67,285,470\"},"
                + "{\"name\": \"whole\", \"mode\": \"stream\", \"area\": \"100,60,300,480\"}"
                + "]}").getBytes(StandardCharsets.UTF_8));

        String json = runCommandLine(
                "src/test/resources/technology/tabula/twotables.pdf",
                "-p", "1", "--plan", planFile.getAbsolutePath(), "-f", "JSON");

        JsonArray tables = JsonParser.parseString(json).getAsJsonArray();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < tables.size(); i++) {
            names.add(tables.get(i).getAsJsonObject().get("region_name").getAsString());
        }
        // "whole" is larger, so it wins the characters it fully covers; the
        // framed table is still extracted from the characters only it covers.
        assertTrue(names.contains("whole"));
    }

    @Test
    public void missingPlanFileIsReported() {
        try {
            org.apache.commons.cli.CommandLine line = new org.apache.commons.cli.DefaultParser()
                    .parse(CommandLineApp.buildOptions(),
                            new String[] { "x.pdf", "--plan", "/nonexistent/plan.json" });
            new CommandLineApp(new StringBuilder(), line);
            fail("Expected ParseException");
        } catch (ParseException expected) {
            assertTrue(expected.getMessage().contains("plan"));
        }
    }

    @Test
    public void tableWithoutRegionHasNullRegionName() {
        Table table = Table.empty();
        assertNull(table.getRegionName());

        table.setRegionName("r");
        assertEquals("r", table.getRegionName());
    }

    // ------------------------------------------------------------------
    // Helpers

    private String runCommandLine(String... args) throws Exception {
        org.apache.commons.cli.CommandLine line = new org.apache.commons.cli.DefaultParser()
                .parse(CommandLineApp.buildOptions(), args);
        StringBuilder out = new StringBuilder();
        new CommandLineApp(out, line).extractTables(line);
        return out.toString();
    }

    private static void assertParseError(String json) {
        try {
            ExtractionPlan.fromJson(json);
            fail("Expected ParseException for: " + json);
        } catch (ParseException expected) {
            // ok
        }
    }

    private static Page syntheticPage(float height, float width, List<TextElement> textElements) {
        RectangleSpatialIndex<TextElement> index = new RectangleSpatialIndex<>();
        for (TextElement textElement : textElements) {
            index.add(textElement);
        }
        return Page.Builder.newInstance()
                .withPageDims(PageDims.of(0f, 0f, width, height))
                .withRotation(0)
                .withNumber(1)
                .withPdPage(null)
                .withPdDocument(null)
                .withTextElements(textElements)
                .withRulings(new ArrayList<Ruling>())
                .withMinCharWidth(7f)
                .withMinCharHeight(7f)
                .withIndex(index)
                .build();
    }

    private static List<TextElement> chars(TextElement... textElements) {
        List<TextElement> list = new ArrayList<>();
        for (TextElement textElement : textElements) {
            list.add(textElement);
        }
        return list;
    }

    private static TextElement charAt(String text, float top, float left, float width, float height) {
        return new TextElement(top, left, width, height, null, 10f, text, 2f);
    }

    private static RegionSpec region(String name, float top, float left, float bottom, float right) {
        return new RegionSpec(name, top, left, bottom, right, "stream");
    }

    private static RegionSpec latticeRegion(String name, float top, float left, float bottom, float right) {
        return new RegionSpec(name, top, left, bottom, right, "lattice");
    }

    /**
     * Builds a plan whose regions all use stream mode; the first argument, if
     * it is a number, overrides {@code minimum_coverage}.
     */
    private static ExtractionPlan plan(Object... arguments) {
        double minimumCoverage = ExtractionPlan.DEFAULT_MINIMUM_COVERAGE;
        int start = 0;
        if (arguments.length > 0 && arguments[0] instanceof Number) {
            minimumCoverage = ((Number) arguments[0]).doubleValue();
            start = 1;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"minimum_coverage\": ").append(minimumCoverage).append(", \"regions\": [");
        for (int i = start; i < arguments.length; i++) {
            if (i > start) {
                sb.append(',');
            }
            sb.append(((RegionSpec) arguments[i]).toJson());
        }
        sb.append("]}");

        try {
            return ExtractionPlan.fromJson(sb.toString());
        } catch (ParseException e) {
            throw new AssertionError(e);
        }
    }

    private static final class RegionSpec {
        private final String name;
        private final float top;
        private final float left;
        private final float bottom;
        private final float right;
        private final String mode;

        private RegionSpec(String name, float top, float left, float bottom, float right, String mode) {
            this.name = name;
            this.top = top;
            this.left = left;
            this.bottom = bottom;
            this.right = right;
            this.mode = mode;
        }

        private String toJson() {
            return "{\"name\": \"" + name + "\", \"mode\": \"" + mode + "\", \"top\": " + top
                    + ", \"left\": " + left + ", \"bottom\": " + bottom + ", \"right\": " + right + "}";
        }
    }
}
