// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.pmtiles;

import org.openstreetmap.josm.tools.Logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * One remote PMTiles archive, addressed directly over HTTP range requests.
 *
 * <p><b>Why this replaces {@code ch.poole.geo.pmtiles.Reader}.</b> The bundled
 * reader computes a tile's id with {@code Hilbert.zxyToIndex}, which truncates its
 * accumulator to {@code int} on every iteration (still true in upstream 0.3.7).
 * Above {@code 2^31 - 1} the id wraps negative, and z16 holds {@code 4^16 == 2^32}
 * tiles — so from z16 down, {@code Reader.getTile} looks up an id that cannot
 * exist and returns null. Measured on the live {@code public_imagery-right}
 * archive: ~700 z16 tiles are baked and NONE were reachable, which is why the
 * plugin silently rendered z15 as its finest detail and tracks looked jagged at
 * full zoom. See {@link TileId#zxyToIndex(int, long, long)}.
 *
 * <p>Because the reader's directory handling is package-private there is no way to
 * correct the id from outside it, so this class does the three things the reader
 * did: read the header, hold the tile index, and range-read a tile body. Tile
 * decoding (gzip + MVT) stays in {@code PmtilesTileLoader}.
 *
 * <p><b>Two things this buys beyond fixing z16.</b> Tile existence is answered from
 * the decoded index — {@link #hasTile(int, long, long)} needs no TILE fetch, so the
 * fetch path does not have to probe-and-miss to discover what is there. And range
 * reads go through {@link #readRange}, which sees the real HTTP status code, so an
 * expired presigned URL is detected as a genuine 403 instead of by matching "403"
 * inside an exception message.
 *
 * <p><b>The index is followed all the way down.</b> A v3 archive too large for its
 * index to fit in the root spills the rest into LEAF DIRECTORIES, reached through
 * root entries with {@code runLength == 0}. This class used to read the root only
 * and treat those pointers as "no tile", which reported every tile behind them as
 * absent — no error, no tiles, an empty map after a download that looked like it
 * worked. It went unnoticed because the public per-facing bakes (~1300 tiles, a
 * ~3 KB root) have no leaves at all, while an organization's full bake is mostly
 * leaves. See {@link #find(long)}.
 *
 * <p>Not thread-safe on {@link #open()}; callers serialize construction (the tile
 * loader does this under its own monitor). Reads after opening are safe.
 */
public final class PmtilesArchive {

    /** Hard cap on a single tile body, so a corrupt directory entry cannot make us
     * allocate wildly. Real tiles here are single-digit KB. */
    private static final int MAX_TILE_BYTES = 16 * 1024 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** Depth limit on the root -> leaf -> ... walk. The v3 spec uses one leaf level
     * in practice; this only stops a malformed archive looping forever. */
    private static final int MAX_DIR_DEPTH = 4;

    /** Decoded leaf directories to keep, keyed by their offset. A leaf is ~16 KB of
     * arrays, so this bounds the index cache at a few MB while a download over one
     * area re-hits the same handful of leaves constantly. */
    private static final int LEAF_CACHE_ENTRIES = 64;

    private final String url;
    private PmtilesDirectory.Header header;
    /** The root directory. For a small archive this is the whole index; for a large
     * one its {@code runLength == 0} entries point at leaf directories. */
    private Dir root;

    /**
     * One decoded directory — root or leaf.
     *
     * <p>Entry starts are ascending: PMTiles v3 requires directories sorted by tile
     * id, and {@link #of} asserts it rather than assuming it, because the binary
     * search below silently returns the WRONG tile otherwise.
     */
    private static final class Dir {
        final long[] starts;
        final long[] runs;
        final long[] offsets;
        final long[] lengths;

        private Dir(long[] starts, long[] runs, long[] offsets, long[] lengths) {
            this.starts = starts;
            this.runs = runs;
            this.offsets = offsets;
            this.lengths = lengths;
        }

        static Dir of(List<PmtilesDirectory.Entry> entries, String what) throws IOException {
            int n = entries.size();
            long[] starts = new long[n];
            long[] runs = new long[n];
            long[] offsets = new long[n];
            long[] lengths = new long[n];
            for (int i = 0; i < n; i++) {
                PmtilesDirectory.Entry e = entries.get(i);
                starts[i] = e.tileId;
                runs[i] = e.runLength;
                offsets[i] = e.offset;
                lengths[i] = e.length;
                if (i > 0 && starts[i] < starts[i - 1]) {
                    // Refuse rather than return subtly wrong geometry.
                    throw new IOException("PMTiles " + what + " is not sorted by tile id");
                }
            }
            return new Dir(starts, runs, offsets, lengths);
        }

        /** Index of the last entry whose start is <= {@code tileId}, or -1.
         * That entry is the only candidate: it either covers the id in its run, or
         * (runLength 0) is the leaf directory the id would live in. */
        int locate(long tileId) {
            int i = Arrays.binarySearch(starts, tileId);
            if (i >= 0) {
                return i;
            }
            return -i - 2;
        }
    }

    public PmtilesArchive(String url) {
        this.url = url;
    }

    /** Fetch + parse the header and the root directory. One range read each; leaf
     * directories are fetched lazily, per tile lookup, and cached. */
    public void open() throws IOException {
        byte[] head = readRange(0, PmtilesDirectory.HEADER_BYTES - 1);
        PmtilesDirectory.Header h = PmtilesDirectory.parseHeader(head);
        if (h.specVersion != 3) {
            throw new IOException("unsupported PMTiles spec version " + h.specVersion);
        }
        this.root = readDir(h, h.rootOffset, h.rootLength, "root directory");
        this.header = h;

        org.openstreetmap.josm.plugins.maprizon.MaprizonLog.info("opened " + redact(url)
                + " z" + h.minZoom + "-" + h.maxZoom
                + " rootEntries=" + root.starts.length
                + " rootTiles=" + rootTileCount()
                + " leafDirs=" + h.leafDirsLength + "B"
                + (h.isFullyInRoot() ? "" : " (followed on demand)"));
    }

    /** Range-read, decompress and decode one directory. */
    private Dir readDir(PmtilesDirectory.Header h, long offset, long length, String what)
            throws IOException {
        byte[] raw = readRange(offset, offset + length - 1);
        byte[] dir = h.internalCompression == PmtilesDirectory.COMPRESSION_GZIP
                ? PmtilesTileLoader.gunzip(raw)
                : raw;
        return Dir.of(PmtilesDirectory.decodeEntries(dir), what);
    }

    /**
     * Decoded leaf directories, most-recently-used last, bounded to
     * {@link #LEAF_CACHE_ENTRIES}. Accessed only under {@code this}.
     */
    private final LinkedHashMap<Long, Dir> leafCache =
            new LinkedHashMap<Long, Dir>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Dir> eldest) {
                    return size() > LEAF_CACHE_ENTRIES;
                }
            };

    private synchronized Dir leafDir(long offset, long length) throws IOException {
        Dir cached = leafCache.get(offset);
        if (cached != null) {
            return cached;
        }
        Dir d = readDir(header, header.leafDirsOffset + offset, length, "leaf directory");
        leafCache.put(offset, d);
        return d;
    }

    /**
     * Resolve a tile id to {@code {offset, length}} of its body, or null when the
     * archive holds no tile there.
     *
     * <p><b>This is what was missing, and it is why a large archive looked empty.</b>
     * A v3 directory entry with {@code runLength == 0} is not "no tile" — it is a
     * pointer to a LEAF DIRECTORY holding that stretch of the index. Small archives
     * (the public per-facing bakes: ~1300 tiles, a ~3 KB root) never produce one, so
     * root-only lookups worked and looked correct. A big archive — an org's whole
     * bake — spills most of its index into leaves, and treating those pointers as
     * absent reported EVERY tile behind them as missing: no error, no tiles, an
     * empty map after a download that appeared to succeed.
     */
    private long[] find(long tileId) throws IOException {
        Dir dir = root;
        for (int depth = 0; depth < MAX_DIR_DEPTH; depth++) {
            if (dir == null) {
                return null;
            }
            int i = dir.locate(tileId);
            if (i < 0) {
                return null;
            }
            long run = dir.runs[i];
            if (run > 0) {
                // Tile entry: several consecutive ids can share one deduplicated
                // body, so the run must actually cover this id.
                return (tileId >= dir.starts[i] && tileId < dir.starts[i] + run)
                        ? new long[]{dir.offsets[i], dir.lengths[i]}
                        : null;
            }
            if (dir.lengths[i] <= 0) {
                return null; // empty leaf pointer: nothing addressed here
            }
            dir = leafDir(dir.offsets[i], dir.lengths[i]);
        }
        throw new IOException("PMTiles directory nested deeper than " + MAX_DIR_DEPTH
                + " levels for tile id " + tileId);
    }

    public boolean isOpen() {
        return header != null;
    }

    public PmtilesDirectory.Header header() {
        return header;
    }

    public int minZoom() {
        return header.minZoom;
    }

    public int maxZoom() {
        return header.maxZoom;
    }

    /** Tile-id slots addressed by the ROOT directory (runs expanded). Excludes
     * anything behind a leaf directory, so on a large archive it is a floor, not a
     * total — it exists for diagnostics, not for logic. */
    public long rootTileCount() {
        long total = 0;
        for (long r : root.runs) {
            if (r > 0) {
                total += r;
            }
        }
        return total;
    }

    /**
     * Does this archive hold a tile at {@code (z,x,y)}?
     *
     * <p>Answers from the cached index without fetching the TILE, but it may fetch
     * one leaf DIRECTORY the first time a region is asked about — which is why it
     * throws. It used to promise "no I/O"; that promise was only keepable because
     * the index was being treated as root-only, which is the defect this class now
     * fixes.
     */
    public boolean hasTile(int z, long x, long y) throws IOException {
        return find(TileId.toTileId(z, x, y)) != null;
    }

    /**
     * Raw (still-compressed) tile body, or {@code null} when the archive holds no
     * tile at this address.
     *
     * <p>Returning null for "absent" preserves the contract
     * {@code PmtilesTileLoader} already relies on to tell "nothing baked here" from
     * "present but empty".
     */
    public byte[] getTile(int z, long x, long y) throws IOException {
        long[] at = find(TileId.toTileId(z, x, y));
        if (at == null) {
            return null;
        }
        long len = at[1];
        if (len <= 0) {
            return null;
        }
        if (len > MAX_TILE_BYTES) {
            throw new IOException("implausible tile length " + len + " at z" + z
                    + "/" + x + "/" + y);
        }
        long from = header.tileDataOffset + at[0];
        return readRange(from, from + len - 1);
    }

    /**
     * HTTP range read.
     *
     * <p>Deliberately checks {@code getResponseCode()} rather than letting
     * {@code getInputStream()} throw: the status is the difference between "this
     * presigned URL expired" (403 → re-sign) and "no such object" (404 → absent),
     * and recovering it from an exception message was previously a substring match.
     */
    byte[] readRange(long from, long to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestProperty("Range", "bytes=" + from + "-" + to);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                throw new PmtilesForbiddenException("403 for " + redact(url));
            }
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " for " + redact(url));
            }
            try (InputStream in = c.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Strip the query string before logging: for org archives it carries the
     * presigning signature, which does not belong in JOSM's log. */
    private static String redact(String u) {
        int q = u.indexOf('?');
        return q < 0 ? u : u.substring(0, q) + "?<signature-redacted>";
    }

    /** Thrown for a 403, so the tile loader can re-sign on a real status code
     * instead of pattern-matching an exception message. */
    public static final class PmtilesForbiddenException extends IOException {
        private static final long serialVersionUID = 1L;

        PmtilesForbiddenException(String msg) {
            super(msg);
        }
    }
}
