# Movies

A list of well-rated movies built from IMDb's public datasets, with the country each movie
comes from. It is updated automatically every day and can be browsed two ways:

- **On the web:** <https://dimap443.github.io/Movies/>. Works in any browser, including on a phone.
- **On Windows:** the desktop app `Movies.exe`. It can also run the update itself.

## Which movies are listed

A title is on the list when, in IMDb's data:

- its type contains "movie" (feature films and TV movies), and
- it is rated **6.8 or higher** by **at least 999 voters**.

Each movie shows its title, year, genre, rating, number of votes, country and the date it was
added to the list. Click a title on the web page, or double-click a row in the app, to open its
IMDb page.

## How the update works

The update runs on GitHub every day, and also when you press **Update** in the desktop app:

1. **Download.** It fetches `title.basics.tsv.gz` and `title.ratings.tsv.gz` from
   [IMDb's datasets](https://datasets.imdbws.com/). A file is downloaded only if IMDb has
   changed it since the last update. If neither file changed and the last build was complete,
   the update stops here.
2. **Filter.** It keeps the movies from the basics file and the titles with a rating of 6.8+
   and 999+ votes from the ratings file. Only titles in both lists go any further.
3. **Look up countries.** Countries already known from the previous list are reused. The
   others are looked up on [Wikidata](https://www.wikidata.org/) by IMDb ID first, then on
   [TMDb](https://www.themoviedb.org/) for titles Wikidata doesn't know. The TMDb step needs an
   API token (see [Configuration](#configuration)) and is skipped without one. If a lookup
   fails, those countries stay blank and the next update tries again.
4. **Save.** It writes `merged.tsv`. A movie keeps its original *Date added*. A movie new to
   the list gets today's date.

### On GitHub

The workflow `.github/workflows/update.yml` runs twice a day. The second run catches a missed
first one and changes nothing if the first one ran. Each run:

1. builds the program and runs the update without a window (`--update`),
2. commits `merged.tsv` if it changed, and
3. publishes `web/index.html` together with the new `merged.tsv` to GitHub Pages.

It can also be started by hand from the repository's **Actions** tab ("Run workflow"). Pushing
a change to `web/`, `merged.tsv` or the workflow republishes the site without an update.

## Using the list

The web page and the desktop app behave the same way:

- **Filter** shows only rows containing the text you type, in any column.
- **Hide country** hides every movie from that country, including co-productions that involve
  it, and movies with no known country, since they may be from it too. India is listed first;
  the other countries follow alphabetically.
- **Sorting:** click a column header to sort, and click again to reverse. When sorting by year,
  movies from the same year are ordered by *Date added*, newest first, so a year's latest
  additions lead it.
- **New movies:** movies added today are highlighted in green for the whole day and shown at the
  top until you sort by a column.

## The desktop app

`Movies.exe` is a Windows app with its own Java runtime built in, so Java doesn't need to be
installed. Keep the whole `Movies` folder together: the exe needs its `app` and `runtime`
folders.

- On startup, it shows the list it has. A `merged.tsv` placed next to `Movies.exe` is loaded if
  it is newer than the app's own copy. Each build of the exe includes the latest `merged.tsv`.
- **Update** runs the update described above on your PC. That takes a few minutes the first
  time, since IMDb's files are large.
- The app keeps its data in `%LOCALAPPDATA%\Movies`: `merged.tsv`, the downloaded IMDb files,
  and `downloads.properties`, which records which versions were downloaded.

## Building

The program is a single Java class, `src/main/java/org/example/FilterAndJoinTsv.java`, built with
Maven and JDK 25.

```
mvn clean package
```

This produces `target\dist\Movies\Movies.exe` with the current `merged.tsv` copied next to it.
Run `git pull` first to include the latest daily update. Packaging the exe uses `jpackage` and
Windows commands, so it must be built on Windows. Add `-Dexec.skip=true` to build only the jar (in `target/libs`), as
the GitHub workflow does.

Run from the IDE, the program keeps its data in the working directory instead of
`%LOCALAPPDATA%\Movies`.

To run the update without a window:

```
java --enable-preview -cp "target/libs/*" org.example.FilterAndJoinTsv --update
```

## Configuration

- **TMDb token** (optional): the program reads it from the `TMDB_API_TOKEN` environment variable,
  or from a `tmdb.txt` file in the data directory. `tmdb.txt` is git-ignored, so the token is
  never committed. On GitHub, it is the repository secret `TMDB_API_TOKEN`.
- **Rating and vote thresholds:** `SECOND_COL2_THRESHOLD` (6.8) and `SECOND_COL3_THRESHOLD`
  (999) in `FilterAndJoinTsv.java`.

## Files

| File                                              | What it is                                             |
|---------------------------------------------------|--------------------------------------------------------|
| `src/main/java/org/example/FilterAndJoinTsv.java` | The desktop app and the update logic                   |
| `web/index.html`                                  | The web page; it loads `merged.tsv` from the same site |
| `merged.tsv`                                      | The current list, updated daily by the workflow        |
| `.github/workflows/update.yml`                    | The daily update and the GitHub Pages publish          |
| `pom.xml`                                         | The Maven build, including packaging `Movies.exe`      |

`merged.tsv` has no header row. Its columns are IMDb's `title.basics` columns (ID, type,
title, original title, adult, year, end year, runtime, genres), then rating and votes, then
country and *Date added*. `\N` marks a missing value.
