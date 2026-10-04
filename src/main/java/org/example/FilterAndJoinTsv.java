package org.example;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.RowFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.Desktop;


public class FilterAndJoinTsv extends JFrame {

    // URLs and filenames
    private static final String FIRST_GZ_URL = "https://datasets.imdbws.com/title.basics.tsv.gz";
    private static final String SECOND_GZ_URL = "https://datasets.imdbws.com/title.ratings.tsv.gz";
    private static final String FIRST_FILE = "first.tsv";
    private static final String SECOND_FILE = "second.tsv";
    private static final String MERGED_FILE = "merged.tsv";

    // Thresholds for second file
    private static final double SECOND_COL2_THRESHOLD = 6.8;
    private static final double SECOND_COL3_THRESHOLD = 999.0;

    private static final boolean FIRST_HAS_HEADER = false;
    private static final boolean SECOND_HAS_HEADER = false;

    private static final Logger LOG = Logger.getLogger(FilterAndJoinTsv.class.getName());

    private final DefaultTableModel tableModel;
    private final TableRowSorter<DefaultTableModel> sorter;
    private final JTextField filterField;
    private final JButton updateButton;
    private final JLabel statusLabel;

    public FilterAndJoinTsv() throws IOException {
        super("Movie Data Updater");

        // Table model: Movie title, year, genre, rating, viewers
        String[] columnNames = {"ID", "Title", "Year", "Genre", "Rating", "Voted"};  // Added
        tableModel = new DefaultTableModel(columnNames, 0) {
            // Report numeric types so the sorter compares numbers, not strings ("10000" < "9999")
            @Override
            public Class<?> getColumnClass(int column) {
                return switch (column) {
                    case 2, 5 -> Integer.class;  // Year, Voted
                    case 4 -> Double.class;      // Rating
                    default -> String.class;
                };
            }
        };
        // GUI components
        JTable table = new JTable(tableModel);

        sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);  // enable sorting and filtering [web:92][web:90][web:91]

        // NEW: HIDE COLUMN 0 (IMDb ID)
        table.getColumnModel().getColumn(0).setMinWidth(0);
        table.getColumnModel().getColumn(0).setMaxWidth(0);
        table.getColumnModel().getColumn(0).setWidth(0);
        table.getColumnModel().getColumn(0).setPreferredWidth(0);
        table.getTableHeader().getColumnModel().getColumn(0).setWidth(0);

// NEW: Make table READ-ONLY
        table.setEnabled(true);                    // Selection works
        table.setDefaultEditor(Object.class, null); // No editing allowed

        // NEW: Double-click to open IMDb page
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {  // Double-click
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        int modelRow = table.convertRowIndexToModel(row);  // Account for sorting/filtering
                        String imdbId = (String) tableModel.getValueAt(modelRow, 0);  // First column (index 0)

                        if (imdbId != null && !imdbId.isEmpty()) {
                            try {
                                String imdbUrl = "https://www.imdb.com/title/" + imdbId;
                                Desktop.getDesktop().browse(new URI(imdbUrl));  // Opens default browser
                            } catch (Exception ex) {
                                JOptionPane.showMessageDialog(FilterAndJoinTsv.this,
                                        "Cannot open browser: " + ex.getMessage(),
                                        "Error", JOptionPane.ERROR_MESSAGE);
                            }
                        }
                    }
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);  // scrollable table [web:96][web:99]

        // Filter field
        filterField = new JTextField(20);
        filterField.addActionListener(_ -> applyFilter());

        JButton filterButton = new JButton("Apply filter");
        filterButton.addActionListener(_ -> applyFilter());

        // Update button
        updateButton = new JButton("Update");
        updateButton.addActionListener(this::runUpdate);

        statusLabel = new JLabel("Ready");

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(updateButton);
        topPanel.add(new JLabel("Filter:"));
        topPanel.add(filterField);
        topPanel.add(filterButton);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(statusLabel, BorderLayout.WEST);

        setLayout(new BorderLayout());
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);

        // NEW: Load existing data on startup
        try {
            loadExistingDataOnStartup();  // Add this line
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    // Add this to the constructor, right before setLocationRelativeTo(null);
    private void loadExistingDataOnStartup() throws IOException {
        Path mergedPath = Paths.get(MERGED_FILE);
        if (Files.exists(mergedPath) && Files.size(mergedPath) > 0) {
            try {
                loadMergedFileIntoTable();
                statusLabel.setText("Loaded existing data from " + MERGED_FILE);
            } catch (IOException e) {
                statusLabel.setText("Error loading existing data: " + e.getMessage());
                LOG.log(Level.SEVERE, "Failed to load " + MERGED_FILE, e);
            }
        } else {
            statusLabel.setText("No existing data - press Update to download");
        }
    }

    private void applyFilter() {
        String text = filterField.getText();
        if (text == null || text.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            // Quote the input so characters like ( [ * + are matched literally instead of as regex syntax
            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(text)));  // case‑insensitive
        }
    }

    private void runUpdate(ActionEvent evt) {
        updateButton.setEnabled(false);
        statusLabel.setText("Updating...");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {  // avoid blocking EDT [web:97][web:94]
            @Override
            protected Void doInBackground() throws Exception {
                // Step 0: download and decompress
                downloadAndDecompress(FIRST_GZ_URL, FIRST_FILE);
                downloadAndDecompress(SECOND_GZ_URL, SECOND_FILE);

                // Steps 1–2: filter
                Map<String, String[]> firstFiltered = filterFirstFile();
                Map<String, String[]> secondFiltered = filterSecondFile();

                // Steps 3–4: merge and save
                mergeAndSave(firstFiltered, secondFiltered);

                return null;
            }

            @Override
            protected void done() {
                try {
                    get(); // rethrow exceptions
                    loadMergedFileIntoTable();
                    statusLabel.setText("Update complete");
                } catch (Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                    LOG.log(Level.SEVERE, "Update failed", ex);
                } finally {
                    updateButton.setEnabled(true);
                }
            }
        };

        worker.execute();
    }

    // -- Existing logic below (slightly adapted to throw InterruptedException) ---

    private static void downloadAndDecompress(String gzUrl, String outputFile)
            throws IOException, InterruptedException {
        Path outputPath = Paths.get(outputFile);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();  // modern HTTP client [web:62][web:81]

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gzUrl))
                .timeout(Duration.ofMinutes(5))
                .build();

        try (InputStream in = client.send(request, HttpResponse.BodyHandlers.ofInputStream()).body();
             BufferedInputStream bis = new BufferedInputStream(in);
             GZIPInputStream gzip = new GZIPInputStream(bis);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8));
             BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {

            // Decode via a Reader so multi-byte UTF-8 characters are never split across buffer boundaries
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
    }

    // Step 1: first file filter – column 2 contains "movie"
    private static Map<String, String[]> filterFirstFile() throws IOException {
        Map<String, String[]> data = new HashMap<>();
        Path path = Paths.get(FilterAndJoinTsv.FIRST_FILE);

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                if (firstLine && FIRST_HAS_HEADER) {
                    firstLine = false;
                    continue;
                }
                firstLine = false;

                String[] columns = line.split("\t", -1);
                if (columns.length > 1 && columns[1].contains("movie")) {
                    String key = columns[0];
                    data.put(key, columns);
                }
            }
        }
        return data;
    }

    // Step 2: second file filter – col2 >= 6.8 AND col3 >= 999
    private static Map<String, String[]> filterSecondFile() throws IOException {
        Map<String, String[]> data = new HashMap<>();
        Path path = Paths.get(FilterAndJoinTsv.SECOND_FILE);

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                if (firstLine && SECOND_HAS_HEADER) {
                    firstLine = false;
                    continue;
                }
                firstLine = false;

                String[] columns = line.split("\t", -1);
                if (columns.length > 2 &&
                        isNumericGreaterThanOrEqual(columns[1], SECOND_COL2_THRESHOLD) &&
                        isNumericGreaterThanOrEqual(columns[2], SECOND_COL3_THRESHOLD)) {
                    String key = columns[0];
                    data.put(key, columns);
                }
            }
        }
        return data;
    }

    // Steps 3–4: merge and save
    private static void mergeAndSave(Map<String, String[]> firstData,
                                     Map<String, String[]> secondData) throws IOException {
        Path outputPath = Paths.get(FilterAndJoinTsv.MERGED_FILE);

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String[]> entry : firstData.entrySet()) {
                String key = entry.getKey();
                String[] firstCols = entry.getValue();
                String[] secondCols = secondData.get(key);

                if (secondCols != null) { // inner join
                    StringBuilder sb = new StringBuilder();
                    sb.append(key);

                    for (int i = 1; i < firstCols.length; i++) {
                        sb.append('\t').append(firstCols[i]);
                    }
                    for (int i = 1; i < secondCols.length; i++) {
                        sb.append('\t').append(secondCols[i]);
                    }

                    writer.write(sb.toString());
                    writer.newLine();
                }
            }
        }
    }

    private static boolean isNumericGreaterThanOrEqual(String value, double threshold) {
        try {
            double num = Double.parseDouble(value);
            return num >= threshold;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // Load merged.tsv into table (Movie title, year, rating, viewers)
    // Fixed column mapping for IMDb datasets
    private void loadMergedFileIntoTable() throws IOException {
        tableModel.setRowCount(0);  // clear old data

        Path path = Paths.get(FilterAndJoinTsv.MERGED_FILE);
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split("\t", -1);

                // IMDb mapping from merged.tsv:
                // cols[0] = tConst (key)
                // cols[2] = primaryTitle (movie title)

                // ... ratings data appended later ...

                if (cols.length >= 5) {  // Ensure we have enough columns
                    String IMDB_ID = cols[0]; //
                    String title = cols[2];        // primaryTitle
                    Integer year = parseIntOrNull(cols[5]);                        // Year
                    String genre = cols.length > 8 ? cols[8] : "";                 // Genre
                    Double rating = cols.length > 9 ? parseDoubleOrNull(cols[9]) : null;   // Rating
                    Integer voted = cols.length > 10 ? parseIntOrNull(cols[10]) : null;    // Voted

                    tableModel.addRow(new Object[]{IMDB_ID, title, year, genre, rating, voted});
                }
            }
        }
    }

    // IMDb uses "\N" for missing values; those become null (empty cell, sorted first)
    private static Integer parseIntOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDoubleOrNull(String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static void main() {
        SwingUtilities.invokeLater(() -> {
            try {
                new FilterAndJoinTsv().setVisible(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
