package org.jqassistant.contrib.plugin.d3jsreport;

import com.buschmais.jqassistant.core.analysis.api.Result;
import com.buschmais.jqassistant.core.analysis.api.rule.ExecutableRule;
import com.buschmais.jqassistant.core.report.api.AbstractReportPlugin;
import com.buschmais.jqassistant.core.report.api.ReportContext;
import com.buschmais.jqassistant.core.report.api.ReportException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class D3JSReportPlugin extends AbstractReportPlugin {

    public static final String DIAGRAM_BASE_DIR = "/diagram/";
    public static final String DIAGRAM_LIB_DIR = "/diagram/lib/";

    private ReportContext reportContext;
    private Map<String, Object> properties;
    private String reportBaseDir;

    @Override
    public void configure(ReportContext reportContext, Map<String, Object> properties) {
        this.reportContext = reportContext;
        this.properties = properties;
        this.reportBaseDir = reportContext.getReportDirectory("d3js/").getAbsolutePath() + "/";
    }

    @Override
    public void setResult(Result<? extends ExecutableRule> result) throws ReportException {
        ExecutableRule rule = result.getRule();
        String ruleId = rule.getId();

        String ruleName = ruleId.replaceAll(":", "_");

        String diagramType = (String) rule.getReport().getProperties().get("diagram-type");
        if (diagramType == null) {
            throw new ReportException("Diagram type not specified for rule " + ruleId);
        }

        String dataFormat = (String) rule.getReport().getProperties().get("data-format");
        if (dataFormat == null) {
            throw new ReportException("Data export format not specified for rule " + ruleId);
        }
        DataType dataExportType;
        try {
            dataExportType = DataType.valueOf(dataFormat.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ReportException("Illegal data export format " + dataFormat + " for rule " + ruleId, e);
        }

        String diagramFileNamePrefix = rule.getId().replaceAll(":", "_");

        switch (dataExportType) {
            case CSV:
                exportCsvData(result, new File(reportBaseDir + diagramFileNamePrefix));
                break;
            case JSON:
                exportJsonData(result, new File(reportBaseDir + diagramFileNamePrefix));
                break;
        }

        try {
            URL htmlUrl = exportAsHtml(rule, diagramType);
            reportContext.addReport("D3JS " + ruleName + " " + diagramType + "-Diagram", result.getRule(), ReportContext.ReportType.LINK, htmlUrl);
        } catch (IOException | URISyntaxException e) {
            throw new ReportException("Cannot export html for " + rule.getId(), e);
        }

        URL imageUrl = exportAsImage(reportContext, rule);
            reportContext.addReport("D3JS " + ruleName + " " + diagramType + "-Diagram", result.getRule(), ReportContext.ReportType.IMAGE, imageUrl);
    }

    /**
     * Exports the result of a query to a json file (data.json). The resulting json file will consist of a list of objects,
     * each storing the columns of the resulting row.
     *
     * @param result The data to export.
     * @param directory The directory to export the data to.
     */
    private void exportJsonData(Result<? extends ExecutableRule> result, File directory) throws ReportException {
        StringBuilder jsonBuilder = new StringBuilder();
        List<String> columnNames = result.getColumnNames();
        jsonBuilder.append("[").append("\n");

        for (Map<String, Object> row : result.getRows()) {
            jsonBuilder.append("  {\n");
            jsonBuilder.append(columnNames
                .stream()
                .map(c -> "    " + c + ": " + row.get(c))
                .collect(Collectors.joining(",\n")));
            jsonBuilder.append("  },\n");
        }

        jsonBuilder.append("]");

        export(directory, "data" + DataType.JSON.getFileSuffix(), jsonBuilder.toString());
    }

    /**
     * Exports the result of a query to a csv file (data.csv). The resulting csv file will consist of a header of the resulting columns
     * and one row per result row.
     *
     * @param result The data to export.
     * @param directory The directory to export the data to.
     */
    private void exportCsvData(Result<? extends ExecutableRule> result, File directory) throws ReportException {
        StringBuilder csvBuilder = new StringBuilder();
        List<String> columnNames = result.getColumnNames();
        csvBuilder.append(columnNames.stream().collect(Collectors.joining(",")));
        csvBuilder.append("\n");
        for (Map<String, Object> row : result.getRows()) {
            csvBuilder.append(columnNames
                .stream()
                .map(row::get)
                .map(Object::toString)
                .collect(Collectors.joining(",")));
            csvBuilder.append("\n");
        }
        export(directory, "data" + DataType.CSV.getFileSuffix(), csvBuilder.toString());
    }

    /**
     * Exports data to a file with the specified name to the specified directory.
     *
     * @param directory The directory to export the file to.
     * @param fileName The file name of the data to export.
     * @param fileContent The content of the file to export.
     */
    private void export(File directory, String fileName, String fileContent) throws ReportException {
        File file = new File(directory, fileName);
        try {
            FileUtils.writeStringToFile(file, fileContent);
        } catch (IOException e) {
            throw new ReportException("Cannot write data to " + file.getPath(), e);
        }
    }

    /**
     * Exports the HTML files required by the given chat, i.e., the specific diagram folder and the lib folder-
     *
     * @param rule The executed rule.
     * @param diagramType The diagram type.
     *
     * @return The export URL.
     *
     * @throws IOException If the source or target dir can't be accessed.
     * @throws URISyntaxException If the URO is invalid.
     */
    private URL exportAsHtml(ExecutableRule rule, String diagramType) throws IOException, URISyntaxException {
        String diagramFileNamePrefix = rule.getId().replaceAll(":", "_");

        final String libTargetDir = reportBaseDir + "/lib/";
        final String diagramTargetDir = reportBaseDir + "/" + diagramFileNamePrefix + "/";

        copyFromJar(DIAGRAM_LIB_DIR, libTargetDir);
        copyFromJar(DIAGRAM_BASE_DIR + diagramType, diagramTargetDir);

        return new URL("file://" + diagramTargetDir + "diagram.html");
    }

    private URL exportAsImage(ReportContext reportContext, ExecutableRule rule) {
        String diagramFileNamePrefix = rule.getId().replaceAll(":", "_");


        return null;
    }

    /**
     * Copies the content of the diagram resource folder for the specific diagram including the lib folder to the target.
     *
     * @param source The source path.
     * @param target The target path.
     *
     * @throws URISyntaxException In case that the URI syntax is invalid.
     * @throws IOException In case that the paths could not be accessed.
     */
    private void copyFromJar(String source, String target) throws URISyntaxException, IOException {
        URI resource = getClass().getResource("").toURI();
        FileSystem fileSystem = FileSystems.newFileSystem(
            resource,
            Collections.<String, String>emptyMap()
        );

        final Path jarPath = fileSystem.getPath(source);
        final Path targetPath = Paths.get(target);

        Files.walkFileTree(jarPath, new SimpleFileVisitor<Path>() {

            private Path currentTarget;

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                currentTarget = targetPath.resolve(jarPath.relativize(dir).toString());
                Files.createDirectories(currentTarget);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, targetPath.resolve(jarPath.relativize(file).toString()), REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }

        });
        fileSystem.close();
    }

    /**
     * Enum representing the available data types.
     */
    enum DataType {
        CSV(".csv"),
        JSON(".json");

        private final String fileSuffix;

        DataType(String fileSuffix) {
            this.fileSuffix = fileSuffix;
        }

        public String getFileSuffix() {
            return fileSuffix;
        }
    }
}
