package org.example;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import javax.swing.RowFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serial;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.Desktop;


public final class FilterAndJoinTsv extends JFrame {

    @Serial
    private static final long serialVersionUID = 1L;

    // URLs and filenames
    private static final String FIRST_GZ_URL = "https://datasets.imdbws.com/title.basics.tsv.gz";
    private static final String SECOND_GZ_URL = "https://datasets.imdbws.com/title.ratings.tsv.gz";
    // The downloads are kept compressed and read directly, so an unchanged file needs no re-download
    private static final String FIRST_GZ_FILE = "title.basics.tsv.gz";
    private static final String SECOND_GZ_FILE = "title.ratings.tsv.gz";
    private static final String MERGED_FILE = "merged.tsv";
    // Per downloaded file: the server's ETag and Last-Modified (sent back so an unchanged file
    // answers 304 with no body), and the version merged.tsv was last completely built from
    private static final String DOWNLOAD_STATE_FILE = "downloads.properties";
    // In the download state: whether that complete build included the TMDb country lookup
    private static final String TMDB_MERGED_KEY = "tmdb.merged";
    // The packaged .exe (jpackage sets jpackage.app-path) keeps its files in %LOCALAPPDATA%\Movies,
    // since its install folder may not be writable; run from the IDE they stay in the working directory
    private static final Path DATA_DIR = System.getProperty("jpackage.app-path") == null
            ? Paths.get("")
            : Paths.get(System.getenv("LOCALAPPDATA"), "Movies");

    // Country of origin comes from Wikidata (P495), matched on the IMDb ID (P345)
    private static final String WIKIDATA_SPARQL_URL = "https://query.wikidata.org/sparql";
    private static final String WIKIDATA_USER_AGENT = "Movies2/1.0 (https://github.com/Dimap443/Movies)";
    private static final int WIKIDATA_BATCH_SIZE = 2000;

    // TMDb fills in titles Wikidata doesn't know. The API token is read from the TMDB_API_TOKEN
    // environment variable or the git-ignored tmdb.txt file; without one this step is skipped.
    private static final String TMDB_MOVIE_URL = "https://api.themoviedb.org/3/movie/";  // accepts IMDb IDs
    private static final String TMDB_TOKEN_ENV = "TMDB_API_TOKEN";
    private static final String TMDB_TOKEN_FILE = "tmdb.txt";
    private static final int TMDB_THREADS = 8;          // stays well under TMDb's ~50 requests/second
    private static final int TMDB_MAX_ATTEMPTS = 4;     // retries for HTTP 429 (rate limited)
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

    // Columns of the IMDb downloads used here; both files start with the IMDb ID
    private static final int IMDB_ID_COLUMN = 0;
    private static final int BASICS_TYPE_COLUMN = 1;      // titleType: movie, tvMovie, short, ...
    private static final int RATINGS_AVERAGE_COLUMN = 1;  // averageRating
    private static final int RATINGS_VOTES_COLUMN = 2;    // numVotes

    // merged.tsv columns: the title.basics columns, then the ratings, country and date added.
    // Its first column is the IMDb ID too (IMDB_ID_COLUMN).
    private static final int MERGED_TITLE_COLUMN = 2;    // primaryTitle
    private static final int MERGED_YEAR_COLUMN = 5;     // startYear
    private static final int MERGED_GENRES_COLUMN = 8;
    private static final int MERGED_RATING_COLUMN = 9;
    private static final int MERGED_VOTES_COLUMN = 10;
    private static final int MERGED_COUNTRY_COLUMN = 11;
    private static final int MERGED_DATE_ADDED_COLUMN = 12;  // yyyy-MM-dd, when the movie joined the list

    // Desktop table columns, in this order
    private static final String[] TABLE_COLUMN_NAMES =
            {"ID", "Title", "Year", "Genre", "Rating", "Voted", "Country", "Date added"};
    private static final int ID_TABLE_COLUMN = 0;  // hidden
    private static final int YEAR_TABLE_COLUMN = 2;
    private static final int RATING_TABLE_COLUMN = 4;
    private static final int VOTED_TABLE_COLUMN = 5;
    private static final int COUNTRY_TABLE_COLUMN = 6;
    private static final int DATE_ADDED_TABLE_COLUMN = 7;

    // Frequently selected countries, listed at the top of "Hide country" before the alphabetical rest
    private static final List<String> FREQUENT_COUNTRIES = List.of("India");

    // Thresholds for second file
    private static final double SECOND_COL2_THRESHOLD = 6.8;
    private static final double SECOND_COL3_THRESHOLD = 999.0;

    // Both IMDb files start with a header row (tconst, titleType, ...)
    private static final boolean FIRST_HAS_HEADER = true;
    private static final boolean SECOND_HAS_HEADER = true;

    private static final Logger LOG = Logger.getLogger(FilterAndJoinTsv.class.getName());

    // Shared by the IMDb downloads and the TMDb lookups. Wikidata has its own, HTTP/1.1-only client.
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // Progress messages go to standard output (java.util.logging's console handler uses stderr)
    private static void log(String message) {
        System.out.println(java.time.LocalTime.now().withNano(0) + " " + message);
    }

    private static void logStart(String action) {
        log("START " + action);
    }

    private static void logEnd(String action) {
        log("END   " + action);
    }

    private static final Color NEW_MOVIE_COLOR = new Color(200, 255, 200);

    private final transient Set<String> newMovieIds = new HashSet<>();  // IMDb ids added today
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final transient TableRowSorter<DefaultTableModel> sorter;
    private final JTextField filterField;
    private final JComboBox<String> hideCountryBox;
    private final JButton updateButton;
    private final JLabel statusLabel;

    private static Path dataFile(String name) {
        return DATA_DIR.resolve(name);
    }

    // A merged.tsv put next to the packaged .exe is copied into the data directory if it is newer
    // than the copy there (or there is none yet), so the app starts with that data
    private static void importMergedFileBesideExe() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null) {
            return;
        }
        Path besideExe = Paths.get(appPath).resolveSibling(MERGED_FILE);
        Path target = dataFile(MERGED_FILE);
        try {
            if (Files.exists(besideExe) && (!Files.exists(target)
                    || Files.getLastModifiedTime(besideExe).compareTo(Files.getLastModifiedTime(target)) > 0)) {
                Files.copy(besideExe, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                log("Imported " + besideExe);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not import " + besideExe, e);
        }
    }

    public FilterAndJoinTsv() throws IOException {
        super("Movie Data Updater");
        Files.createDirectories(DATA_DIR);
        importMergedFileBesideExe();

        tableModel = new DefaultTableModel(TABLE_COLUMN_NAMES, 0) {
            // Report numeric types so the sorter compares numbers, not strings ("10000" < "9999")
            @Override
            public Class<?> getColumnClass(int column) {
                return switch (column) {
                    case YEAR_TABLE_COLUMN, VOTED_TABLE_COLUMN -> Integer.class;
                    case RATING_TABLE_COLUMN -> Double.class;
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
        table = new JTable(tableModel) {
            // Tint rows for new movies
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (!isRowSelected(row)) {
                    String id = (String) tableModel.getValueAt(convertRowIndexToModel(row), ID_TABLE_COLUMN);
                    c.setBackground(newMovieIds.contains(id) ? NEW_MOVIE_COLOR : getBackground());
                }
                return c;
            }
        };

        sorter = new TableRowSorter<>(tableModel) {
            // Within a year, the most recently added movies come first, so today's additions
            // lead their year
            @Override
            public void toggleSortOrder(int column) {
                super.toggleSortOrder(column);
                if (column == YEAR_TABLE_COLUMN) {
                    setSortKeys(List.of(getSortKeys().getFirst(),
                            new SortKey(DATE_ADDED_TABLE_COLUMN, SortOrder.DESCENDING)));
                }
            }
        };
        table.setRowSorter(sorter);  // enable sorting and filtering

        // Starting column widths, and a tooltip explaining each column header
        int[] preferredWidths = {0, 280, 60, 160, 60, 70, 140, 90};
        for (int i = 0; i < preferredWidths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(preferredWidths[i]);
        }
        String[] headerTips = {
                "IMDb ID",
                "Primary title",
                "Release year. Sorting also puts the newest additions first within a year",
                "Genres",
                "Average IMDb rating",
                "Number of votes",
                "Country of origin",
                "Date this title joined the list"
        };
        JTableHeader header = table.getTableHeader();
        header.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int viewColumn = header.columnAtPoint(e.getPoint());
                header.setToolTipText(viewColumn < 0
                        ? null
                        : headerTips[table.convertColumnIndexToModel(viewColumn)]);
            }
        });

        // Hide the IMDb ID column (still used for the double-click link and the highlights)
        table.getColumnModel().getColumn(ID_TABLE_COLUMN).setMinWidth(0);
        table.getColumnModel().getColumn(ID_TABLE_COLUMN).setMaxWidth(0);
        table.getColumnModel().getColumn(ID_TABLE_COLUMN).setWidth(0);
        table.getColumnModel().getColumn(ID_TABLE_COLUMN).setPreferredWidth(0);

        table.setDefaultEditor(Object.class, null);  // read-only; selection still works

        // Double-click to open the IMDb page
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {  // Double-click
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        int modelRow = table.convertRowIndexToModel(row);  // Account for sorting/filtering
                        String imdbId = (String) tableModel.getValueAt(modelRow, ID_TABLE_COLUMN);

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

        JScrollPane scrollPane = new JScrollPane(table);

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

        // Load existing data on startup
        try {
            loadExistingDataOnStartup();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private void loadExistingDataOnStartup() throws IOException {
        Path mergedPath = dataFile(MERGED_FILE);
        if (Files.exists(mergedPath) && Files.size(mergedPath) > 0) {
            try {
                logStart("Load existing " + MERGED_FILE);
                loadMergedFileIntoTable();
                logEnd("Load existing " + MERGED_FILE + " (" + tableModel.getRowCount() + " rows)");
                markNewMovies();
                statusLabel.setText("Loaded existing data from " + MERGED_FILE
                        + (newMovieIds.isEmpty() ? "" : " - " + newMovieIds.size() + " new movies highlighted"));
            } catch (IOException e) {
                log("Load existing " + MERGED_FILE + " FAILED: " + e.getMessage());
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
            // Co-productions list several countries ("France,India"); hide the movie if any of them matches.
            // Movies with no known country are hidden too, since they may be from the hidden country
            // (they stay in merged.tsv).
            filters.add(new RowFilter<>() {
                @Override
                public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                    String countries = entry.getStringValue(COUNTRY_TABLE_COLUMN);
                    return !countries.isEmpty() && !Arrays.asList(countries.split(",")).contains(hiddenCountry);
                }
            });
        }

        sorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));
    }

    // Rebuild the "Hide country" choices from the table, keeping the current selection if still present
    private void refreshCountryChoices() {
        Set<String> countries = new TreeSet<>();
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            String value = (String) tableModel.getValueAt(row, COUNTRY_TABLE_COLUMN);
            if (value != null && !value.isEmpty()) {
                countries.addAll(Arrays.asList(value.split(",")));
            }
        }

        String selected = (String) hideCountryBox.getSelectedItem();
        DefaultComboBoxModel<String> choices = new DefaultComboBoxModel<>();
        choices.addElement("");
        for (String frequent : FREQUENT_COUNTRIES) {
            if (countries.remove(frequent)) {  // only if present in the data, and not listed twice
                choices.addElement(frequent);
            }
        }
        countries.forEach(choices::addElement);
        hideCountryBox.setModel(choices);
        hideCountryBox.setSelectedItem(choices.getIndexOf(selected) >= 0 ? selected : "");
    }

    private void runUpdate(ActionEvent evt) {
        updateButton.setEnabled(false);
        statusLabel.setText("Updating...");

        // Runs in the background so the window stays responsive; progress messages go to the status bar
        SwingWorker<UpdateResult, String> worker = new SwingWorker<>() {
            private String unchangedSince;  // IMDb's Last-Modified, when the result is UNCHANGED

            @Override
            protected UpdateResult doInBackground() throws Exception {
                UpdateResult result = performUpdate(this::publish);
                if (result == UpdateResult.UNCHANGED) {
                    unchangedSince = lastModified(loadDownloadState());
                }
                return result;
            }

            @Override
            protected void process(List<String> messages) {
                statusLabel.setText(messages.getLast());  // only the latest one matters
            }

            @Override
            protected void done() {
                try {
                    UpdateResult result = get(); // rethrow exceptions
                    if (result == UpdateResult.UNCHANGED) {
                        // Table and highlights stay as they are
                        statusLabel.setText("IMDb data unchanged since " + unchangedSince + " - nothing to update");
                        return;
                    }
                    logStart("Load " + MERGED_FILE + " into table");
                    loadMergedFileIntoTable();
                    logEnd("Load " + MERGED_FILE + " into table (" + tableModel.getRowCount() + " rows)");
                    markNewMovies();

                    statusLabel.setText((result == UpdateResult.COMPLETE
                            ? "Update complete"
                            : "Update complete (country lookup incomplete - see log)")
                            + " - " + newMovieIds.size() + " new movies highlighted");
                } catch (Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                    log("Update FAILED: " + ex.getMessage());
                    LOG.log(Level.SEVERE, "Update failed", ex);
                } finally {
                    updateButton.setEnabled(true);
                }
            }
        };

        worker.execute();
    }

    // Like the web page: movies whose Date added is today are highlighted for the whole of that day,
    // however often or late the app is opened. They are moved to the top, and any column sort is
    // dropped so they stay there.
    private void markNewMovies() {
        String today = LocalDate.now().toString();
        newMovieIds.clear();
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            if (today.equals(tableModel.getValueAt(row, DATE_ADDED_TABLE_COLUMN))) {
                newMovieIds.add((String) tableModel.getValueAt(row, ID_TABLE_COLUMN));
            }
        }

        int top = 0;
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            if (newMovieIds.contains((String) tableModel.getValueAt(row, ID_TABLE_COLUMN))) {
                tableModel.moveRow(row, row, top++);
            }
        }
        sorter.setSortKeys(null);
        table.scrollRectToVisible(table.getCellRect(0, 0, true));
        tableModel.fireTableDataChanged();  // repaint with the highlights
    }

    // -- Existing logic below (slightly adapted to throw InterruptedException) ---

    private enum UpdateResult { UNCHANGED, COMPLETE, COUNTRIES_INCOMPLETE }

    // The whole update, shared by the Update button and the headless --update run:
    // download, filter, look up countries, then merge and save merged.tsv
    private static UpdateResult performUpdate() throws IOException, InterruptedException {
        return performUpdate(_ -> { });
    }

    // As above, also reporting each step to progress (shown in the window's status bar)
    private static UpdateResult performUpdate(Consumer<String> progress)
            throws IOException, InterruptedException {
        logStart("Update");
        progress.accept("Downloading IMDb files...");
        Properties state = loadDownloadState();

        // Step 0: download whichever files IMDb has regenerated since the last update
        logStart("Download " + FIRST_GZ_URL);
        boolean firstChanged = downloadIfChanged(FIRST_GZ_URL, FIRST_GZ_FILE, state);
        logEnd("Download " + FIRST_GZ_URL + (firstChanged ? "" : " (unchanged, skipped)"));
        logStart("Download " + SECOND_GZ_URL);
        boolean secondChanged = downloadIfChanged(SECOND_GZ_URL, SECOND_GZ_FILE, state);
        logEnd("Download " + SECOND_GZ_URL + (secondChanged ? "" : " (unchanged, skipped)"));
        saveDownloadState(state);

        // Nothing to redo if merged.tsv was already completely built from these exact files,
        // unless a TMDb token has appeared since: it can fill in countries Wikidata lacked
        String tmdbToken = loadTmdbToken();
        boolean tmdbNewlyAvailable = tmdbToken != null
                && !Boolean.parseBoolean(state.getProperty(TMDB_MERGED_KEY));
        if (isMergedUpToDate(state) && !tmdbNewlyAvailable) {
            logEnd("Update (IMDb data unchanged since " + lastModified(state) + ")");
            return UpdateResult.UNCHANGED;
        }

        // Steps 1–2: filter. The ratings file goes first: it is far more selective, so the much
        // larger basics file then only keeps the titles that can be in the join.
        logStart("Filter " + SECOND_GZ_FILE);
        progress.accept("Filtering ratings...");
        Map<String, String[]> secondFiltered = filterSecondFile();
        logEnd("Filter " + SECOND_GZ_FILE + " (" + secondFiltered.size() + " rows kept)");
        logStart("Filter " + FIRST_GZ_FILE);
        progress.accept("Filtering titles (" + secondFiltered.size() + " ratings kept)...");
        Map<String, String[]> firstFiltered = filterFirstFile(secondFiltered.keySet());
        logEnd("Filter " + FIRST_GZ_FILE + " (" + firstFiltered.size() + " rows kept)");

        // Step 2b: country of origin, only for titles that survive the join: Wikidata first,
        // then TMDb for the titles Wikidata doesn't know.
        // Not fatal: if a source is unavailable its titles are left blank.
        Set<String> joinedKeys = new HashSet<>(firstFiltered.keySet());
        joinedKeys.retainAll(secondFiltered.keySet());
        // Countries already in the previous merged.tsv are kept; only the rest are looked up.
        Map<String, String> countries = loadPreviousColumn(MERGED_COUNTRY_COLUMN);
        joinedKeys.removeAll(countries.keySet());
        boolean countriesLoaded = true;
        progress.accept("Looking up countries on Wikidata (" + joinedKeys.size() + ")...");
        logStart("Wikidata country lookup (" + joinedKeys.size() + " titles)");
        try {
            Map<String, String> wikidataCountries = fetchWikidataCountries(joinedKeys);
            countries.putAll(wikidataCountries);
            logEnd("Wikidata country lookup (" + wikidataCountries.size() + " found)");
        } catch (IOException e) {
            countriesLoaded = false;
            logEnd("Wikidata country lookup FAILED: " + e.getMessage());
            LOG.log(Level.WARNING, "Wikidata country lookup failed", e);
        }

        if (tmdbToken != null) {
            Set<String> missing = new HashSet<>(joinedKeys);
            missing.removeAll(countries.keySet());
            logStart("TMDb country lookup (" + missing.size() + " titles)");
            progress.accept("Looking up remaining countries on TMDb (" + missing.size() + ")...");
            try {
                Map<String, String> tmdbCountries = fetchTmdbCountries(missing, tmdbToken);
                countries.putAll(tmdbCountries);
                logEnd("TMDb country lookup (" + tmdbCountries.size() + " found)");
            } catch (IOException e) {
                countriesLoaded = false;
                logEnd("TMDb country lookup FAILED: " + e.getMessage());
                LOG.log(Level.WARNING, "TMDb country lookup failed", e);
            }
        } else {
            log("TMDb country lookup skipped (no token)");
        }

        // Steps 3–4: merge and save
        logStart("Merge and save " + MERGED_FILE);
        progress.accept("Saving " + MERGED_FILE + "...");
        mergeAndSave(firstFiltered, secondFiltered, countries, loadPreviousDatesAdded());
        logEnd("Merge and save " + MERGED_FILE);

        // Only a complete build counts: after a failed country lookup the next Update
        // reprocesses (and retries the lookup) even if IMDb hasn't changed
        if (countriesLoaded) {
            for (String file : List.of(FIRST_GZ_FILE, SECOND_GZ_FILE)) {
                state.setProperty(file + ".merged", fileVersion(state, file));
            }
            state.setProperty(TMDB_MERGED_KEY, Boolean.toString(tmdbToken != null));
            saveDownloadState(state);
        }
        logEnd("Update");

        return countriesLoaded ? UpdateResult.COMPLETE : UpdateResult.COUNTRIES_INCOMPLETE;
    }

    // When IMDb last regenerated the downloaded data, for "unchanged since" messages
    private static String lastModified(Properties state) {
        return state.getProperty(FIRST_GZ_FILE + ".lastModified", "the last update");
    }

    // Download gzUrl to outputFile unless the copy on disk is still current: the ETag and
    // Last-Modified saved in state are sent back, and the server answers 304 (no body) if the
    // file hasn't changed. Returns true if a new copy was downloaded.
    private static boolean downloadIfChanged(String gzUrl, String outputFile, Properties state)
            throws IOException, InterruptedException {
        Path outputPath = dataFile(outputFile);
        Path partPath = dataFile(outputFile + ".part");  // never leave a half-written file in place

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(gzUrl))
                .timeout(Duration.ofMinutes(5));
        if (Files.exists(outputPath)) {
            String etag = state.getProperty(outputFile + ".etag");
            String lastModified = state.getProperty(outputFile + ".lastModified");
            if (etag != null) {
                request.header("If-None-Match", etag);
            }
            if (lastModified != null) {
                request.header("If-Modified-Since", lastModified);
            }
        }

        try {
            HttpResponse<Path> response = HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofFile(partPath));
            if (response.statusCode() == 304) {
                return false;
            }
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading " + gzUrl);
            }
            replaceAtomically(partPath, outputPath);

            for (String header : List.of("ETag", "Last-Modified")) {
                String key = outputFile + (header.equals("ETag") ? ".etag" : ".lastModified");
                response.headers().firstValue(header).ifPresentOrElse(
                        value -> state.setProperty(key, value), () -> state.remove(key));
            }
            return true;
        } finally {
            Files.deleteIfExists(partPath);
        }
    }

    // What identifies a downloaded file's version: its ETag, else its Last-Modified (null if neither)
    private static String fileVersion(Properties state, String file) {
        return state.getProperty(file + ".etag", state.getProperty(file + ".lastModified"));
    }

    // True if merged.tsv was completely built from the downloaded files now on disk
    private static boolean isMergedUpToDate(Properties state) {
        if (!Files.exists(dataFile(MERGED_FILE))) {
            return false;
        }
        for (String file : List.of(FIRST_GZ_FILE, SECOND_GZ_FILE)) {
            String version = fileVersion(state, file);
            if (version == null || !version.equals(state.getProperty(file + ".merged"))) {
                return false;
            }
        }
        return true;
    }

    // The download state file in the data directory; empty if it doesn't exist yet
    private static Properties loadDownloadState() throws IOException {
        Properties state = new Properties();
        Path path = dataFile(DOWNLOAD_STATE_FILE);
        if (Files.exists(path)) {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                state.load(reader);
            }
        }
        return state;
    }

    private static void saveDownloadState(Properties state) throws IOException {
        Path partPath = dataFile(DOWNLOAD_STATE_FILE + ".part");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(partPath, StandardCharsets.UTF_8)) {
                state.store(writer, "IMDb download versions - written by FilterAndJoinTsv");
            }
            replaceAtomically(partPath, dataFile(DOWNLOAD_STATE_FILE));
        } finally {
            Files.deleteIfExists(partPath);
        }
    }

    // Swap a completely written file into place, so a crash never leaves a half-written target
    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // Read a downloaded .gz file as text. Decoding via a Reader means multibyte UTF-8 characters
    // are never split across buffer boundaries.
    private static BufferedReader openGzip(String file) throws IOException {
        return new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(dataFile(file))), 65536),
                StandardCharsets.UTF_8));
    }


    // Step 1: first file filter – column 2 contains "movie", for titles in eligibleIds only
    private static Map<String, String[]> filterFirstFile(Set<String> eligibleIds) throws IOException {
        Map<String, String[]> data = new HashMap<>();
        try (BufferedReader reader = openGzip(FIRST_GZ_FILE)) {
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                if (firstLine && FIRST_HAS_HEADER) {
                    firstLine = false;
                    continue;
                }
                firstLine = false;

                String[] columns = line.split("\t", -1);
                if (columns.length > BASICS_TYPE_COLUMN && columns[BASICS_TYPE_COLUMN].contains("movie")
                        && eligibleIds.contains(columns[IMDB_ID_COLUMN])) {
                    data.put(columns[IMDB_ID_COLUMN], columns);
                }
            }
        }
        return data;
    }

    // Step 2: second file filter – col2 >= 6.8 AND col3 >= 999
    private static Map<String, String[]> filterSecondFile() throws IOException {
        Map<String, String[]> data = new HashMap<>();
        try (BufferedReader reader = openGzip(SECOND_GZ_FILE)) {
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                if (firstLine && SECOND_HAS_HEADER) {
                    firstLine = false;
                    continue;
                }
                firstLine = false;

                String[] columns = line.split("\t", -1);
                if (columns.length > RATINGS_VOTES_COLUMN &&
                        isNumericGreaterThanOrEqual(columns[RATINGS_AVERAGE_COLUMN], SECOND_COL2_THRESHOLD) &&
                        isNumericGreaterThanOrEqual(columns[RATINGS_VOTES_COLUMN], SECOND_COL3_THRESHOLD)) {
                    data.put(columns[IMDB_ID_COLUMN], columns);
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
                .version(HttpClient.Version.HTTP_1_1)  // avoids HTTP/2 stream resets from Wikidata's proxy
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

            HttpResponse<String> response = sendWithRetry(client, request);
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

    // Wikidata often answers 429/5xx under load; retry those with growing delays.
    // The sleep is a deliberate backoff between attempts, not busy-waiting.
    @SuppressWarnings("BusyWait")
    private static HttpResponse<String> sendWithRetry(HttpClient client, HttpRequest request)
            throws IOException, InterruptedException {
        int attempt = 1;
        while (true) {
            String failure;
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if ((status != 429 && status < 500) || attempt == 5) {
                    return response;
                }
                failure = "HTTP " + status;
            } catch (IOException e) {
                // e.g. the server resetting the stream mid-response
                if (attempt == 5) {
                    throw e;
                }
                failure = e.toString();
            }
            LOG.warning("Wikidata " + failure + ", retrying (attempt " + attempt + ")");
            Thread.sleep(2000L * attempt * attempt);
            attempt++;
        }
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
        Path tokenFile = dataFile(TMDB_TOKEN_FILE);
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
        Map<String, String> data = new HashMap<>();

        // A few lookups per thread are queued at a time, refilled as each one finishes, instead of
        // one task per title up front. close() waits for running lookups; on failure shutdownNow()
        // interrupts them first.
        try (ExecutorService pool = Executors.newFixedThreadPool(TMDB_THREADS)) {
            CompletionService<Map.Entry<String, String>> lookups = new ExecutorCompletionService<>(pool);
            Iterator<String> ids = keys.iterator();
            int inFlight = 0;
            try {
                while (inFlight > 0 || ids.hasNext()) {
                    while (inFlight < TMDB_THREADS * 4 && ids.hasNext()) {
                        String id = ids.next();
                        // The value may be null (no country known); Map.entry() doesn't allow that
                        lookups.submit(() -> new AbstractMap.SimpleEntry<>(id, fetchTmdbCountry(token, id)));
                        inFlight++;
                    }
                    Map.Entry<String, String> result = lookups.take().get();
                    inFlight--;
                    if (result.getValue() != null) {
                        data.put(result.getKey(), result.getValue());
                    }
                }
            } catch (ExecutionException e) {
                pool.shutdownNow();
                if (e.getCause() instanceof IOException io) {
                    throw io;
                }
                throw new IOException(e.getCause());
            } catch (InterruptedException e) {
                pool.shutdownNow();
                throw e;
            }
        }
        return data;
    }

    // One TMDb lookup; null when TMDb has no such movie or no country for it.
    // The sleeps in the retry loop are a bounded back-off after a failed attempt, not busy-waiting.
    @SuppressWarnings("BusyWait")
    private static String fetchTmdbCountry(String token, String imdbId)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TMDB_MOVIE_URL + imdbId))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .build();

        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> response;
            try {
                response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (attempt == TMDB_MAX_ATTEMPTS) {
                    throw e;
                }
                Thread.sleep(1000L * attempt);  // e.g. a dropped connection: back off and retry
                continue;
            }
            int status = response.statusCode();
            if (status == 404) {
                return null;
            }
            if (status == 200) {
                return parseTmdbCountries(response.body());
            }
            if ((status != 429 && status < 500) || attempt == TMDB_MAX_ATTEMPTS) {
                throw new IOException("TMDb lookup of " + imdbId + " failed: HTTP " + status);
            }
            // Rate limited or a server error: wait as long as TMDb asks (Retry-After, in seconds,
            // capped at 30), else a little longer each attempt
            long delaySeconds = response.headers().firstValue("Retry-After")
                    .map(FilterAndJoinTsv::parseRetryAfter)
                    .orElse((long) attempt);
            Thread.sleep(1000L * delaySeconds);
        }
    }

    private static long parseRetryAfter(String value) {
        try {
            return Math.clamp(Long.parseLong(value.trim()), 1L, 30L);
        } catch (NumberFormatException e) {
            return 1L;  // an HTTP date instead of seconds
        }
    }

    // Prefers "origin_country" (["IN"]), falls back to "production_countries" ([{"iso_3166_1":"IN",...}]).
    // Only the movie's own top-level fields are read, not those of its production companies.
    private static String parseTmdbCountries(String json) {
        JSONObject movie = new JSONObject(json);
        Set<String> names = new TreeSet<>();
        JSONArray origin = movie.optJSONArray("origin_country");
        if (origin != null) {
            for (int i = 0; i < origin.length(); i++) {
                addCountry(names, origin.optString(i));
            }
        }
        JSONArray production = movie.optJSONArray("production_countries");
        if (names.isEmpty() && production != null) {
            for (int i = 0; i < production.length(); i++) {
                JSONObject country = production.optJSONObject(i);
                if (country != null) {
                    addCountry(names, country.optString("iso_3166_1"));
                }
            }
        }
        return names.isEmpty() ? null : String.join(",", names);
    }

    private static void addCountry(Set<String> names, String isoCode) {
        if (isoCode.length() == 2) {  // skips blanks
            names.add(countryName(isoCode));
        }
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

    // Date added per movie in the previous merged.tsv. Movies listed there without a date (a file
    // from before this column existed) map to MISSING, so they stay undated instead of looking new.
    // Null if there is no previous merged.tsv: then no date is known, and none is made up.
    private static Map<String, String> loadPreviousDatesAdded() {
        if (!Files.exists(dataFile(MERGED_FILE))) {
            return null;
        }
        Map<String, String> dates = loadPreviousColumn(IMDB_ID_COLUMN);  // every listed movie (ID -> ID)
        dates.replaceAll((_, _) -> MISSING);
        dates.putAll(loadPreviousColumn(MERGED_DATE_ADDED_COLUMN));
        return dates;
    }

    // One column of the previous merged.tsv (IMDb ID -> value), skipping missing values;
    // empty if no file yet
    private static Map<String, String> loadPreviousColumn(int column) {
        Map<String, String> known = new HashMap<>();
        Path path = dataFile(MERGED_FILE);
        if (!Files.exists(path)) {
            return known;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split("\t", -1);
                if (cols.length > column && !cols[column].isEmpty() && !MISSING.equals(cols[column])) {
                    known.put(cols[IMDB_ID_COLUMN], cols[column]);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read column " + column + " of " + MERGED_FILE, e);
        }
        return known;
    }

    // Steps 3–4: merge and save
    private static void mergeAndSave(Map<String, String[]> firstData,
                                     Map<String, String[]> secondData,
                                     Map<String, String> countries,
                                     Map<String, String> datesAdded) throws IOException {
        String today = LocalDate.now().toString();
        Path outputPath = dataFile(FilterAndJoinTsv.MERGED_FILE);
        Path partPath = dataFile(FilterAndJoinTsv.MERGED_FILE + ".part");

        try (BufferedWriter writer = Files.newBufferedWriter(partPath, StandardCharsets.UTF_8)) {
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
                    // date added: kept from the previous build, today for a movie new to the list
                    sb.append('\t').append(datesAdded == null ? MISSING : datesAdded.getOrDefault(key, today));

                    writer.write(sb.toString());
                    writer.newLine();
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(partPath);  // the previous merged.tsv stays in place
            throw e;
        }
        replaceAtomically(partPath, outputPath);
    }

    private static boolean isNumericGreaterThanOrEqual(String value, double threshold) {
        try {
            double num = Double.parseDouble(value);
            return num >= threshold;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // Load merged.tsv into the table. The whole file is read before the table is touched, so if
    // reading fails the rows already shown stay as they were.
    private void loadMergedFileIntoTable() throws IOException {
        List<Object[]> rows = new ArrayList<>();
        Path path = dataFile(FilterAndJoinTsv.MERGED_FILE);
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split("\t", -1);
                if (cols.length <= MERGED_YEAR_COLUMN) {
                    continue;  // too short to be a movie row
                }
                // In TABLE_COLUMN_NAMES order. Files from older versions may lack the last columns.
                rows.add(new Object[]{
                        cols[IMDB_ID_COLUMN],
                        cols[MERGED_TITLE_COLUMN],
                        parseIntOrNull(cols[MERGED_YEAR_COLUMN]),
                        blankIfMissing(column(cols, MERGED_GENRES_COLUMN)),
                        parseDoubleOrNull(column(cols, MERGED_RATING_COLUMN)),
                        parseIntOrNull(column(cols, MERGED_VOTES_COLUMN)),
                        blankIfMissing(column(cols, MERGED_COUNTRY_COLUMN)),
                        blankIfMissing(column(cols, MERGED_DATE_ADDED_COLUMN))});
            }
        }

        tableModel.setRowCount(0);  // clear old data
        for (Object[] row : rows) {
            tableModel.addRow(row);
        }
        refreshCountryChoices();
        applyFilter();  // re-apply the current filters to the reloaded rows
    }

    // One column of a split line; MISSING if the line is too short to have it
    private static String column(String[] cols, int index) {
        return index < cols.length ? cols[index] : MISSING;
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

    // With --update, runs the update once without a window (for the scheduled GitHub Action) and
    // exits non-zero if it failed; otherwise opens the app as usual
    static void main(String[] args) throws IOException, InterruptedException {
        if (List.of(args).contains("--update")) {
            Files.createDirectories(DATA_DIR);
            UpdateResult result = performUpdate();
            log("Result: " + result);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                new FilterAndJoinTsv().setVisible(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
