package com.micatechnologies.minecraft.mcmcp.tools;

/**
 * Which blocks the bulk writes treat as two cells, from facts {@link TwoPartBlocks} reads off a block.
 * No Minecraft class, so the rule is unit-tested on its own.
 *
 * <p>A {@code half} property of {@code lower}/{@code upper} (every door and tall plant) and a bed
 * say outright that a block is two cells. A stored boolean {@code upper} or {@code top} does not: CSM's
 * tall furniture uses one for its upper half, but CSM's cable trays use {@code top} for a tray hung at
 * the top of its one cell (#130), and LBE's tables carry its cabinets' flag (#105). So the flag pairs
 * only with a second piece of evidence: the block is named in {@code writes.pairedBlocks}, or the
 * flagged half drops nothing while the other half drops an item, which is how every real two-cell
 * block keeps a break from handing back two. A flag with neither is only a suspicion: reported, and
 * the cell is written as given.
 */
final class TwoPartRule {

    private TwoPartRule() {
    }

    /** How a write treats a block. */
    enum Verdict {
        /** Written whole: a missing second half is derived. */
        PAIR,
        /** Has a stored upper/top flag and nothing else saying it is two cells: written as given, reported. */
        SUSPECTED,
        /** One cell. */
        SINGLE
    }

    /**
     * @param denied                 named in {@code writes.notPairedBlocks}
     * @param allowed                named in {@code writes.pairedBlocks}
     * @param halfProperty           has a {@code half} property with {@code lower} and {@code upper}
     * @param bed                    is a bed
     * @param storedFlag             has a boolean {@code upper} or {@code top} stored in its metadata
     * @param onlyLowerHalfDrops     with that flag set it drops nothing, and with it clear it drops an item
     */
    static Verdict decide(boolean denied, boolean allowed, boolean halfProperty, boolean bed, boolean storedFlag,
                          boolean onlyLowerHalfDrops) {
        if (denied) {
            return Verdict.SINGLE;
        }
        if (halfProperty || bed) {
            return Verdict.PAIR;
        }
        if (!storedFlag) {
            return Verdict.SINGLE;
        }
        return allowed || onlyLowerHalfDrops ? Verdict.PAIR : Verdict.SUSPECTED;
    }
}
