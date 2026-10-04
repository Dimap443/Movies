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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
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

    // Country of origin comes from Wikidata (P495), matched on the IMDb ID (P345)
    private static final String WIKIDATA_SPARQL_URL = "https://query.wikidata.org/sparql";
    private static final String WIKIDATA_USER_AGENT = "Movies2/1.0 (https://github.com/Dimap443/Movies)";
    private static final int WIKIDATA_BATCH_SIZE = 2000;

    // TMDb fills in titles Wikidata doesn't know. The API token is read from the TMDB_API_TOKEN
    // environment variable or the git-ignored tmdb.token file; without one this step is skipped.
    private static final String TMDB_MOVIE_URL = "https://api.themoviedb.org/3/movie/";  // accepts IMDb IDs
    private static final String TMDB_TOKEN_ENV = "TMDB_API_TOKEN";
    private static final String TMDB_TOKEN_FILE = "tmdb.token";
    private static final int TMDB_THREADS = 8;          // stays well under TMDb's ~50 requests/second
    private static final int TMDB_MAX_ATTEMPTS = 4;     // retries for HTTP 429 (rate limited)
    private static final Pattern TMDB_ORIGIN_COUNTRY = Pattern.compile("\"origin_country\":\\[([^\\]]*)]");
    private static final Pattern TMDB_PRODUCTION_COUNTRIES = Pattern.compile("\"production_countries\":\\[([^\\]]*)]");
    private static final Pattern TMDB_CODE = Pattern.compile("\"([A-Z]{2})\"");
    // Countries whose Java (CLDR) English name differs from the name Wikidata uses
    private static final Map<String, String> WIKIDATA_COUNTRY_NAMES = Map.ofEntries(
            Map.entry("BA", "Bosnia and Herzegovina"),
            Map.entry("BS", "The Bahamas"),
            Map.entry("CD", "Democratic Republic of the Congo"),
            Map.entry("CG", "Republic of the Congo"),
            Map.entry("CI", "Ivory Coast"),
            Map.entry("CN", "People's Republic of China"),
            Map.entry("CZ", "Czech Republic"),
            Map.entry("GM", "The Gambia"),
            Map.entry("HK", "Hong Kong"),
            Map.entry("MM", "Myanmar"),
            Map.entry("MO", "Macau"),
            Map.entry("PS", "Palestine"),
            Map.entry("TR", "Turkey"));

    // IMDb's marker for a missing value
    private static final String MISSING = "\\N";

    private static final int COUNTRY_COLUMN = 6;

    // Thresholds for second file
    private static final double SECOND_COL2_THRESHOLD = 6.8;
    private static final double SECOND_COL3_THRESHOLD = 999.0;

    private static final boolean FIRST_HAS_HEADER = false;
    private static final boolean SECOND_HAS_HEADER = false;

    private static final Logger LOG = Logger.getLogger(FilterAndJoinTsv.class.getName());

    private final DefaultTableModel tableModel;
    private final TableRowSorter<DefaultTableModel> sorter;
    private final JTextField filterField;
    private final JComboBox<String> hideCountryBox;
    private final JButton updateButton;
    private final JLabel statusLabel;

    public FilterAndJoinTsv() throws IOException {
        super("Movie Data Updater");

        // Table model: Movie title, year, genre, rating, viewers
        String[] columnNames = {"ID", "Title", "Year", "Genre", "Rating", "Voted", "Country"};
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

            // Read-only: Integer/Double columns would otherwise pick up Swing's Number editor
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
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

        // Hide movies from one country (choices are filled in from the loaded data)
        hideCountryBox = new JComboBox<>(new String[]{""});
        hideCountryBox.addActionListener(_ -> applyFilter());

        // Update button
        updateButton = new JButton("Update");
        updateButton.addActionListener(this::runUpdate);

        statusLabel = new JLabel("Ready");

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(updateButton);
        topPanel.add(new JLabel("Filter:"));
        topPanel.add(filterField);
        topPanel.add(filterButton);
        topPanel.add(new JLabel("Hide country:"));
        topPanel.add(hideCountryBox);

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
        List<RowFilter<DefaultTableModel, Integer>> filters = new ArrayList<>();

        String text = filterField.getText();
        if (text != null && !text.isEmpty()) {
            // Quote the input so characters like ( [ * + are matched literally instead of as regex syntax
            filters.add(RowFilter.regexFilter("(?i)" + Pattern.quote(text)));  // case‑insensitive
        }

        String hiddenCountry = (String) hideCountryBox.getSelectedItem();
        if (hiddenCountry != null && !hiddenCountry.isEmpty()) {
            // Co-productions list several countries ("France,India"); hide the movie if any of them matches
            filters.add(new RowFilter<>() {
                @Override
                public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                    String countries = entry.getStringValue(COUNTRY_COLUMN);
                    return !Arrays.asList(countries.split(",")).contains(hiddenCountry);
                }
            });
        }

        sorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));
    }

    // Rebuild the "Hide country" choices from the table, keeping the current selection if still present
    private void refreshCountryChoices() {
        Set<String> countries = new TreeSet<>();
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            String value = (String) tableModel.getValueAt(row, COUNTRY_COLUMN);
            if (value != null && !value.isEmpty()) {
                countries.addAll(Arrays.asList(value.split(",")));
            }
        }

        Object selected = hideCountryBox.getSelectedItem();
        DefaultComboBoxModel<String> choices = new DefaultComboBoxModel<>();
        choices.addElement("");
        countries.forEach(choices::addElement);
        hideCountryBox.setModel(choices);
        hideCountryBox.setSelectedItem(countries.contains(selected) ? selected : "");
    }

    private void runUpdate(ActionEvent evt) {
        updateButton.setEnabled(false);
        statusLabel.setText("Updating...");

        // Result: true if the Wikidata country lookup succeeded
        SwingWorker<Boolean, Void> worker = new SwingWorker<>() {  // avoid blocking EDT [web:97][web:94]
            @Override
            protected Boolean doInBackground() throws Exception {
                // Step 0: download and decompress
                downloadAndDecompress(FIRST_GZ_URL, FIRST_FILE);
                downloadAndDecompress(SECOND_GZ_URL, SECOND_FILE);

                // Steps 1–2: filter
                Map<String, String[]> firstFiltered = filterFirstFile();
                Map<String, String[]> secondFiltered = filterSecondFile();

                // Step 2b: country of origin, only for titles that survive the join: Wikidata first,
                // then TMDb for the titles Wikidata doesn't know.
                // Not fatal: if a source is unavailable its titles are left blank.
                Set<String> joinedKeys = new HashSet<>(firstFiltered.keySet());
                joinedKeys.retainAll(secondFiltered.keySet());
                Map<String, String> countries = new HashMap<>();
                boolean countriesLoaded = true;
                try {
                    countries.putAll(fetchWikidataCountries(joinedKeys));
                } catch (IOException e) {
                    countriesLoaded = false;
                    LOG.log(Level.WARNING, "Wikidata country lookup failed", e);
                }

                String tmdbToken = loadTmdbToken();
                if (tmdbToken != null) {
                    Set<String> missing = new HashSet<>(joinedKeys);
                    missing.removeAll(countries.keySet());
                    try {
                        countries.putAll(fetchTmdbCountries(missing, tmdbToken));
                    } catch (IOException e) {
                        countriesLoaded = false;
                        LOG.log(Level.WARNING, "TMDb country lookup failed", e);
                    }
                }

                // Steps 3–4: merge and save
                mergeAndSave(firstFiltered, secondFiltered, countries);

                return countriesLoaded;
            }

            @Override
            protected void done() {
                try {
                    boolean countriesLoaded = get(); // rethrow exceptions
                    loadMergedFileIntoTable();
                    statusLabel.setText(countriesLoaded
                            ? "Update complete"
                            : "Update complete (country lookup incomplete - see log)");
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

            // Decode via a Reader so multibyte UTF-8 characters are never split across buffer boundaries
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

    // Step 2b: country of origin from Wikidata, queried in batches of IMDb IDs.
    // Result per key: comma-separated English country names ("France,Poland"); keys Wikidata
    // doesn't know are absent.
    private static Map<String, String> fetchWikidataCountries(Set<String> keys)
            throws IOException, InterruptedException {
        Map<String, Set<String>> countries = new HashMap<>();
        List<String> ids = new ArrayList<>(keys);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        for (int start = 0; start < ids.size(); start += WIKIDATA_BATCH_SIZE) {
            StringBuilder values = new StringBuilder();
            for (String id : ids.subList(start, Math.min(start + WIKIDATA_BATCH_SIZE, ids.size()))) {
                values.append('"').append(id).append("\" ");
            }
            String query = "SELECT ?imdb ?countryLabel WHERE { VALUES ?imdb { " + values + "} "
                    + "?film wdt:P345 ?imdb ; wdt:P495 ?country . "
                    + "?country rdfs:label ?countryLabel . FILTER(LANG(?countryLabel) = \"en\") }";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(WIKIDATA_SPARQL_URL))
                    .timeout(Duration.ofMinutes(2))
                    .header("Accept", "text/tab-separated-values")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", WIKIDATA_USER_AGENT)  // required by Wikidata's usage policy
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("Wikidata query failed: HTTP " + response.statusCode());
            }

            // Rows look like: "tt0211718"<TAB>"Japan"@en ; the first line is the header
            response.body().lines().skip(1).forEach(line -> {
                String[] columns = line.split("\t", -1);
                if (columns.length >= 2) {
                    countries.computeIfAbsent(parseSparqlLiteral(columns[0]), _ -> new TreeSet<>())
                            .add(parseSparqlLiteral(columns[1]));
                }
            });
        }

        Map<String, String> data = new HashMap<>();
        countries.forEach((id, names) -> data.put(id, String.join(",", names)));
        return data;
    }

    // Turns a SPARQL TSV literal like "Japan"@en into Japan
    private static String parseSparqlLiteral(String value) {
        int first = value.indexOf('"');
        int last = value.lastIndexOf('"');
        if (first < 0 || last <= first) {
            return value;
        }
        return value.substring(first + 1, last).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    // TMDb API token from the environment or the local token file; null if neither is set
    private static String loadTmdbToken() {
        String token = System.getenv(TMDB_TOKEN_ENV);
        Path tokenFile = Paths.get(TMDB_TOKEN_FILE);
        if ((token == null || token.isBlank()) && Files.exists(tokenFile)) {
            try {
                token = Files.readString(tokenFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Cannot read " + TMDB_TOKEN_FILE, e);
            }
        }
        if (token == null || token.isBlank()) {
            LOG.info("No TMDb token (" + TMDB_TOKEN_ENV + " or " + TMDB_TOKEN_FILE + "), skipping TMDb lookup");
            return null;
        }
        return token.trim();
    }

    // Step 2c: country of origin from TMDb, one request per IMDb ID, a few in parallel.
    // Result per key: comma-separated English country names; keys TMDb doesn't know are absent.
    private static Map<String, String> fetchTmdbCountries(Set<String> keys, String token)
            throws IOException, InterruptedException {
        Map<String, String> data = new ConcurrentHashMap<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(TMDB_THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String id : keys) {
                futures.add(pool.submit(() -> {
                    String countries = fetchTmdbCountry(client, token, id);
                    if (countries != null) {
                        data.put(id, countries);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
        return data;
    }

    // One TMDb lookup; null when TMDb has no such movie or no country for it
    private static String fetchTmdbCountry(HttpClient client, String token, String imdbId)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TMDB_MOVIE_URL + imdbId))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .build();

        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 404) {
                return null;
            }
            if (status == 429 && attempt < TMDB_MAX_ATTEMPTS) {
                Thread.sleep(1000L * attempt);  // rate limited: back off and retry
                continue;
            }
            if (status != 200) {
                throw new IOException("TMDb lookup of " + imdbId + " failed: HTTP " + status);
            }
            return parseTmdbCountries(response.body());
        }
    }

    // Prefers "origin_country" (["IN"]), falls back to "production_countries" ([{"iso_3166_1":"IN",...}]).
    // Production companies also have an "origin_country", but as a string, so the [ ] patterns skip it.
    private static String parseTmdbCountries(String json) {
        Set<String> names = new TreeSet<>();
        for (Pattern field : new Pattern[]{TMDB_ORIGIN_COUNTRY, TMDB_PRODUCTION_COUNTRIES}) {
            Matcher array = field.matcher(json);
            if (array.find()) {
                Matcher code = TMDB_CODE.matcher(array.group(1));
                while (code.find()) {
                    names.add(countryName(code.group(1)));
                }
            }
            if (!names.isEmpty()) {
                break;
            }
        }
        return names.isEmpty() ? null : String.join(",", names);
    }

    // ISO code to the English name Wikidata uses, so both sources agree: "IN" -> India, "TR" -> Turkey
    private static String countryName(String isoCode) {
        String override = WIKIDATA_COUNTRY_NAMES.get(isoCode);
        if (override != null) {
            return override;
        }
        String name = Locale.of("", isoCode).getDisplayCountry(Locale.ENGLISH)
                .replace(" & ", " and ");          // "Trinidad & Tobago"
        return name.startsWith("St. ") ? "Saint " + name.substring(4) : name;  // "St. Lucia"
    }

    // Steps 3–4: merge and save
    private static void mergeAndSave(Map<String, String[]> firstData,
                                     Map<String, String[]> secondData,
                                     Map<String, String> countries) throws IOException {
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
                    sb.append('\t').append(countries.getOrDefault(key, MISSING));  // country

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

                if (cols.length > 5) {  // Ensure we have enough columns (cols[5] is read below)
                    String IMDB_ID = cols[0]; //
                    String title = cols[2];        // primaryTitle
                    Integer year = parseIntOrNull(cols[5]);                        // Year
                    String genre = cols.length > 8 ? cols[8] : "";                 // Genre
                    Double rating = cols.length > 9 ? parseDoubleOrNull(cols[9]) : null;   // Rating
                    Integer voted = cols.length > 10 ? parseIntOrNull(cols[10]) : null;    // Voted
                    String country = cols.length > 11 ? blankIfMissing(cols[11]) : "";   // Country

                    tableModel.addRow(new Object[]{IMDB_ID, title, year, genre, rating, voted, country});
                }
            }
        }
        refreshCountryChoices();
    }

    private static String blankIfMissing(String value) {
        return MISSING.equals(value) ? "" : value;
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
