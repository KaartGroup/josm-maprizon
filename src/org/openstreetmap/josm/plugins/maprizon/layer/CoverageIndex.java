// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.layer;

import org.openstreetmap.josm.plugins.maprizon.pmtiles.TileId;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Where imagery exists, before anything is downloaded: per facing, which coarse
 * tiles the archives hold, rolled up to every zoom from 0 to {@link #MAX_ZOOM}.
 *
 * <p>Built from directory reads alone ({@code PmtilesTileLoader.availableTileIds}),
 * never a tile decode. It relies on one property of the bakes, measured rather than
 * assumed ({@code testbed/AvailabilityProbe.java}, 2026-10-29, every public facing):
 * every baked tile has its ancestor baked at every coarser zoom down to the
 * archive's minimum. So "this z12 tile exists" means "imagery exists beneath it",
 * and the overview needs nothing finer than z12.
 *
 * <p>This is PRESENCE, not density. A cell with one image and a cell with ten
 * thousand look the same, so nothing built on it may claim a count.
 * (docs/COVERAGE_AVAILABILITY_PLAN.md, Track A; true counts are Track B.)
 *
 * <p>Immutable once built, so paint can read it from the EDT while the loader
 * thread builds the next one.
 */
final class CoverageIndex {

    /** Finest zoom indexed. A z12 tile is ~10 km across; finer than that, the
     * coverage lines themselves are the better answer. */
    static final int MAX_ZOOM = 12;

    /** facing -> zoom -> packed (x << 32 | y) of every cell holding imagery. */
    private final Map<String, Set<Long>[]> cellsByFacing = new HashMap<>();
    private final int indexedTiles;

    /** @param tileIdsByFacing per facing, PMTiles ids at zoom &lt;= {@link #MAX_ZOOM};
     *                        facings that could not be read are simply absent */
    @SuppressWarnings("unchecked")
    CoverageIndex(Map<String, long[]> tileIdsByFacing) {
        int total = 0;
        for (Map.Entry<String, long[]> e : tileIdsByFacing.entrySet()) {
            Set<Long>[] byZoom = new Set[MAX_ZOOM + 1];
            for (int z = 0; z <= MAX_ZOOM; z++) {
                byZoom[z] = new HashSet<>();
            }
            for (long id : e.getValue()) {
                long[] zxy = TileId.toZxy(id);
                int z = (int) zxy[0];
                if (z > MAX_ZOOM) {
                    continue;
                }
                total++;
                // Roll each tile up to every coarser zoom, so a zoom the archive
                // does not bake (below its minZoom) still has cells.
                for (int cz = 0; cz <= z; cz++) {
                    long[] a = TileId.ancestorAt(z, zxy[1], zxy[2], cz);
                    byZoom[cz].add(pack(a[0], a[1]));
                }
            }
            cellsByFacing.put(e.getKey(), byZoom);
        }
        this.indexedTiles = total;
    }

    /** Cells at zoom {@code z} (clamped to [0, {@link #MAX_ZOOM}]) holding imagery
     * in any of {@code facings}, packed as {@code x << 32 | y}. */
    Set<Long> cellsAt(int z, Collection<String> facings) {
        int cz = Math.max(0, Math.min(MAX_ZOOM, z));
        Set<Long> out = new HashSet<>();
        for (String facing : facings) {
            Set<Long>[] byZoom = cellsByFacing.get(facing);
            if (byZoom != null) {
                out.addAll(byZoom[cz]);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    boolean isEmpty() {
        return indexedTiles == 0;
    }

    /** One line for the log and the layer's info panel. */
    String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(indexedTiles).append(" tiles indexed, z").append(MAX_ZOOM).append(" cells:");
        for (Map.Entry<String, Set<Long>[]> e : cellsByFacing.entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue()[MAX_ZOOM].size());
        }
        return sb.toString();
    }

    static long pack(long x, long y) {
        return (x << 32) | (y & 0xFFFFFFFFL);
    }

    static long unpackX(long packed) {
        return packed >>> 32;
    }

    static long unpackY(long packed) {
        return packed & 0xFFFFFFFFL;
    }
}
