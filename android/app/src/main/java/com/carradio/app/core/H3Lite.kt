// H3Lite.kt
// Minimal pure-Kotlin port of Uber H3 v4.1.0 covering exactly what Car Radio needs:
//   - latLngToCell(lat, lng, res)  (geo -> cell, any res 0-15; we use 7/8/9)
//   - gridDisk(cell, k)            (k-ring neighborhood, pentagon-safe)
//
// Mechanically ported from the verified Swift implementation in
// ios/CarRadio/Sources/Core/H3Lite.swift (itself a faithful port of the H3 C
// source: faceijk.c, coordijk.c, h3Index.c, baseCells.c, algos.c). Lookup
// tables live in H3LiteTables.kt and were mechanically converted from the
// Swift tables.
//
// Output is the canonical lowercase-hex form identical to h3-js
// (e.g. "872830828ffffff"). Validated against official h3-js v4 fixtures in
// H3LiteTest (see android/tools/gen-h3-fixtures.mjs). Re-verify against h3-js
// before shipping if this file or the tables are edited.
//
// Pure Kotlin/JVM — no Android imports.

package com.carradio.app.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

object H3Lite {
    // Constants

    private const val EPSILON = 1e-16
    private const val M_2PI = 2.0 * Math.PI
    private const val M_SIN60 = 0.8660254037844386467637231707529361834714
    private const val M_AP7_ROT_RADS = 0.333473172251832115336090755351601070065900389
    private const val RES0_U_GNOMONIC = 0.38196601125010500003
    private const val M_SQRT7 = 2.6457513110645905905016157536392604257102
    private const val MAX_H3_RES = 15
    private const val INVALID_BASE_CELL = 127

    // Direction digits (Direction enum in the C source)
    private const val CENTER_DIGIT = 0
    private const val K_AXES_DIGIT = 1
    private const val J_AXES_DIGIT = 2
    private const val JK_AXES_DIGIT = 3
    private const val I_AXES_DIGIT = 4
    private const val IK_AXES_DIGIT = 5
    private const val IJ_AXES_DIGIT = 6
    private const val INVALID_DIGIT = 7

    private val unitVecs: Array<IntArray> = arrayOf(
        intArrayOf(0, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 1, 0), intArrayOf(0, 1, 1),
        intArrayOf(1, 0, 0), intArrayOf(1, 0, 1), intArrayOf(1, 1, 0),
    )

    // Public API

    /**
     * Returns the H3 cell containing the given WGS84 coordinate at [res],
     * as a canonical lowercase hex string (identical to h3-js).
     * Throws [IllegalArgumentException] for invalid input (res out of range /
     * non-finite coords).
     */
    fun latLngToCell(lat: Double, lng: Double, res: Int): String {
        require(res in 0..MAX_H3_RES && lat.isFinite() && lng.isFinite()) {
            "invalid latLngToCell input: lat=$lat lng=$lng res=$res"
        }
        val fijk = geoToFaceIjk(lat * Math.PI / 180.0, lng * Math.PI / 180.0, res)
        val h = faceIjkToH3(fijk, res)
        require(h != 0uL) { "latLngToCell failed for lat=$lat lng=$lng res=$res" }
        return h.toString(16)
    }

    /**
     * Returns all cells within grid distance [k] of [cell] (including [cell]).
     * Pentagon-safe (uses the "safe" BFS traversal from algos.c).
     * Ordering is deterministic (origin first, then BFS discovery order) but is
     * NOT guaranteed to match h3-js spiral ordering — treat the result as a set.
     * Returns [cell] unchanged if the input cannot be parsed.
     */
    fun gridDisk(cell: String, k: Int): List<String> {
        val origin = cell.toULongOrNull(16)
        if (k < 0 || origin == null || origin == 0uL) return listOf(cell)
        val distances = HashMap<ULong, Int>()
        val order = ArrayList<ULong>()
        val stack = ArrayDeque<Pair<ULong, Int>>()
        stack.addLast(origin to 0)
        while (stack.isNotEmpty()) {
            val (current, d) = stack.removeLast()
            val prev = distances[current]
            if (prev != null && prev <= d) continue
            if (prev == null) order.add(current)
            distances[current] = d
            if (d >= k) continue
            for (dir in DISK_DIRECTIONS) {
                when (val result = h3NeighborRotations(current, dir, 0)) {
                    is NeighborResult.Success -> stack.addLast(result.index to (d + 1))
                    NeighborResult.Pentagon -> continue // expected when traversing off a pentagon
                    NeighborResult.Invalid -> return listOf(cell) // invalid input index
                }
            }
        }
        return order.map { it.toString(16) }
    }

    // Angle helpers

    private fun posAngleRads(rads: Double): Double {
        var tmp = if (rads < 0.0) rads + M_2PI else rads
        if (rads >= M_2PI) tmp -= M_2PI
        return tmp
    }

    private fun geoAzimuthRads(p1Lat: Double, p1Lng: Double, p2Lat: Double, p2Lng: Double): Double =
        atan2(
            cos(p2Lat) * sin(p2Lng - p1Lng),
            cos(p1Lat) * sin(p2Lat) - sin(p1Lat) * cos(p2Lat) * cos(p2Lng - p1Lng)
        )

    private fun isResolutionClassIII(res: Int): Boolean = res % 2 == 1

    /** C lround semantics: round half away from zero. */
    private fun cRound(v: Double): Int =
        if (v >= 0.0) floor(v + 0.5).toInt() else ceil(v - 0.5).toInt()

    // IJK coordinates (coordijk.c)

    private fun ijkNormalize(c: IntArray) {
        if (c[0] < 0) { c[1] -= c[0]; c[2] -= c[0]; c[0] = 0 }
        if (c[1] < 0) { c[0] -= c[1]; c[2] -= c[1]; c[1] = 0 }
        if (c[2] < 0) { c[0] -= c[2]; c[1] -= c[2]; c[2] = 0 }
        val minVal = minOf(c[0], c[1], c[2])
        if (minVal > 0) { c[0] -= minVal; c[1] -= minVal; c[2] -= minVal }
    }

    /** _hex2dToCoordIJK from coordijk.c (DGGRID quantization). */
    private fun hex2dToCoordIJK(x: Double, y: Double): IntArray {
        val h = intArrayOf(0, 0, 0)
        val a1 = abs(x)
        val a2 = abs(y)

        val x2 = a2 / M_SIN60
        val x1 = a1 + x2 / 2.0

        val m1 = floor(x1).toInt()
        val m2 = floor(x2).toInt()

        val r1 = x1 - m1
        val r2 = x2 - m2

        if (r1 < 0.5) {
            if (r1 < 1.0 / 3.0) {
                if (r2 < (1.0 + r1) / 2.0) {
                    h[0] = m1; h[1] = m2
                } else {
                    h[0] = m1; h[1] = m2 + 1
                }
            } else {
                h[1] = if (r2 < (1.0 - r1)) m2 else m2 + 1
                h[0] = if ((1.0 - r1) <= r2 && r2 < (2.0 * r1)) m1 + 1 else m1
            }
        } else {
            if (r1 < 2.0 / 3.0) {
                h[1] = if (r2 < (1.0 - r1)) m2 else m2 + 1
                h[0] = if ((2.0 * r1 - 1.0) < r2 && r2 < (1.0 - r1)) m1 else m1 + 1
            } else {
                if (r2 < (r1 / 2.0)) {
                    h[0] = m1 + 1; h[1] = m2
                } else {
                    h[0] = m1 + 1; h[1] = m2 + 1
                }
            }
        }

        // fold across the axes if necessary
        if (x < 0.0) {
            if (h[1] % 2 == 0) { // even
                val axisi = h[1] / 2
                val diff = h[0] - axisi
                h[0] = h[0] - 2 * diff
            } else {
                val axisi = (h[1] + 1) / 2
                val diff = h[0] - axisi
                h[0] = h[0] - (2 * diff + 1)
            }
        }
        if (y < 0.0) {
            // C: h->i = h->i - (2 * h->j + 1) / 2  (integer division, j >= 0 here)
            h[0] = h[0] - (2 * h[1] + 1) / 2
            h[1] = -h[1]
        }
        ijkNormalize(h)
        return h
    }

    private fun upAp7(c: IntArray) {
        val i = c[0] - c[2]
        val j = c[1] - c[2]
        c[0] = cRound((3 * i - j) / 7.0)
        c[1] = cRound((i + 2 * j) / 7.0)
        c[2] = 0
        ijkNormalize(c)
    }

    private fun upAp7r(c: IntArray) {
        val i = c[0] - c[2]
        val j = c[1] - c[2]
        c[0] = cRound((2 * i + j) / 7.0)
        c[1] = cRound((3 * j - i) / 7.0)
        c[2] = 0
        ijkNormalize(c)
    }

    private fun downAp7(c: IntArray) {
        // res r unit vectors in res r+1: i={3,0,1} j={1,3,0} k={0,1,3}
        val i = c[0]; val j = c[1]; val k = c[2]
        c[0] = 3 * i + j
        c[1] = 3 * j + k
        c[2] = i + 3 * k
        ijkNormalize(c)
    }

    private fun downAp7r(c: IntArray) {
        // res r unit vectors in res r+1: i={3,1,0} j={0,3,1} k={1,0,3}
        val i = c[0]; val j = c[1]; val k = c[2]
        c[0] = 3 * i + k
        c[1] = i + 3 * j
        c[2] = j + 3 * k
        ijkNormalize(c)
    }

    private fun unitIjkToDigit(ijk: IntArray): Int {
        val c = ijk.copyOf()
        ijkNormalize(c)
        for (digit in 0 until 7) {
            if (c.contentEquals(unitVecs[digit])) return digit
        }
        return INVALID_DIGIT
    }

    private fun rotate60ccw(digit: Int): Int = when (digit) {
        K_AXES_DIGIT -> IK_AXES_DIGIT
        IK_AXES_DIGIT -> I_AXES_DIGIT
        I_AXES_DIGIT -> IJ_AXES_DIGIT
        IJ_AXES_DIGIT -> J_AXES_DIGIT
        J_AXES_DIGIT -> JK_AXES_DIGIT
        JK_AXES_DIGIT -> K_AXES_DIGIT
        else -> digit
    }

    private fun rotate60cw(digit: Int): Int = when (digit) {
        K_AXES_DIGIT -> JK_AXES_DIGIT
        JK_AXES_DIGIT -> J_AXES_DIGIT
        J_AXES_DIGIT -> IJ_AXES_DIGIT
        IJ_AXES_DIGIT -> I_AXES_DIGIT
        I_AXES_DIGIT -> IK_AXES_DIGIT
        IK_AXES_DIGIT -> K_AXES_DIGIT
        else -> digit
    }

    // Table accessors (baseCells.c)

    private fun faceIjkBaseCell(face: Int, i: Int, j: Int, k: Int): Int {
        val idx = (((face * 3 + i) * 3 + j) * 3 + k) * 2
        return H3Tables.faceIjkBaseCells[idx]
    }

    private fun faceIjkBaseCellCcwRot60(face: Int, i: Int, j: Int, k: Int): Int {
        val idx = (((face * 3 + i) * 3 + j) * 3 + k) * 2
        return H3Tables.faceIjkBaseCells[idx + 1]
    }

    private fun isBaseCellPentagon(baseCell: Int): Boolean {
        if (baseCell < 0 || baseCell >= 122) return false
        return H3Tables.baseCellData[baseCell * 7 + 4] == 1
    }

    private fun isBaseCellPolarPentagon(baseCell: Int): Boolean =
        baseCell == 4 || baseCell == 117

    private fun baseCellHomeFace(baseCell: Int): Int = H3Tables.baseCellData[baseCell * 7]

    private fun baseCellIsCwOffset(baseCell: Int, testFace: Int): Boolean =
        H3Tables.baseCellData[baseCell * 7 + 5] == testFace ||
            H3Tables.baseCellData[baseCell * 7 + 6] == testFace

    private fun baseCellNeighbor(baseCell: Int, dir: Int): Int =
        H3Tables.baseCellNeighbors[baseCell * 7 + dir]

    private fun baseCellNeighborRot(baseCell: Int, dir: Int): Int =
        H3Tables.baseCellNeighbor60CCWRots[baseCell * 7 + dir]

    // H3 index bit layout (h3Index.h)

    private const val H3_INIT: ULong = 35_184_372_088_831uL // all digits set to 7
    private const val MODE_OFFSET = 59
    private const val BASE_CELL_OFFSET = 45
    private const val RES_OFFSET = 52

    private fun setMode(h: ULong, mode: ULong): ULong =
        (h and (15uL shl MODE_OFFSET).inv()) or (mode shl MODE_OFFSET)

    private fun setRes(h: ULong, res: Int): ULong =
        (h and (15uL shl RES_OFFSET).inv()) or (res.toULong() shl RES_OFFSET)

    private fun getRes(h: ULong): Int = ((h shr RES_OFFSET) and 15uL).toInt()

    private fun setBaseCell(h: ULong, baseCell: Int): ULong =
        (h and (127uL shl BASE_CELL_OFFSET).inv()) or (baseCell.toULong() shl BASE_CELL_OFFSET)

    private fun getBaseCell(h: ULong): Int = ((h shr BASE_CELL_OFFSET) and 127uL).toInt()

    private fun digitShift(res: Int): Int = (MAX_H3_RES - res) * 3

    private fun getDigit(h: ULong, res: Int): Int = ((h shr digitShift(res)) and 7uL).toInt()

    private fun setDigit(h: ULong, res: Int, digit: Int): ULong {
        val shift = digitShift(res)
        return (h and (7uL shl shift).inv()) or (digit.toULong() shl shift)
    }

    private fun leadingNonZeroDigit(h: ULong): Int {
        val res = getRes(h)
        for (r in 1..res) {
            val d = getDigit(h, r)
            if (d != 0) return d
        }
        return CENTER_DIGIT
    }

    private fun h3Rotate60ccw(hIn: ULong): ULong {
        var h = hIn
        val res = getRes(h)
        for (r in 1..res) {
            h = setDigit(h, r, rotate60ccw(getDigit(h, r)))
        }
        return h
    }

    private fun h3Rotate60cw(hIn: ULong): ULong {
        var h = hIn
        val res = getRes(h)
        for (r in 1..res) {
            h = setDigit(h, r, rotate60cw(getDigit(h, r)))
        }
        return h
    }

    private fun h3RotatePent60ccw(hIn: ULong): ULong {
        var h = hIn
        var foundFirstNonZeroDigit = false
        val res = getRes(h)
        for (r in 1..res) {
            h = setDigit(h, r, rotate60ccw(getDigit(h, r)))
            if (!foundFirstNonZeroDigit && getDigit(h, r) != 0) {
                foundFirstNonZeroDigit = true
                if (leadingNonZeroDigit(h) == K_AXES_DIGIT) {
                    h = h3Rotate60ccw(h)
                }
            }
        }
        return h
    }

    // geo -> FaceIJK (faceijk.c)

    private class FaceIJK(val face: Int, val coord: IntArray)

    private fun geoToFaceIjk(latRads: Double, lngRads: Double, res: Int): FaceIJK {
        // _geoToClosestFace: closest icosahedron face by squared chord distance
        val cosLat = cos(latRads)
        val px = cos(lngRads) * cosLat
        val py = sin(lngRads) * cosLat
        val pz = sin(latRads)

        var face = 0
        var sqd = 5.0
        for (f in 0 until 20) {
            val fx = H3Tables.faceCenterPoint[f * 3]
            val fy = H3Tables.faceCenterPoint[f * 3 + 1]
            val fz = H3Tables.faceCenterPoint[f * 3 + 2]
            val d = (fx - px) * (fx - px) + (fy - py) * (fy - py) + (fz - pz) * (fz - pz)
            if (d < sqd) {
                face = f
                sqd = d
            }
        }

        // _geoToHex2d
        var r = acos(1 - sqd / 2)
        var vx = 0.0
        var vy = 0.0
        if (r >= EPSILON) {
            val fcLat = H3Tables.faceCenterGeo[face * 2]
            val fcLng = H3Tables.faceCenterGeo[face * 2 + 1]
            var theta = posAngleRads(
                H3Tables.faceAxesAzRadsCII0[face] -
                    posAngleRads(geoAzimuthRads(fcLat, fcLng, latRads, lngRads))
            )
            if (isResolutionClassIII(res)) {
                theta = posAngleRads(theta - M_AP7_ROT_RADS)
            }
            r = tan(r)
            r /= RES0_U_GNOMONIC
            repeat(res) { r *= M_SQRT7 }
            vx = r * cos(theta)
            vy = r * sin(theta)
        }
        return FaceIJK(face, hex2dToCoordIJK(vx, vy))
    }

    // FaceIJK -> H3 (h3Index.c _faceIjkToH3)

    private fun faceIjkToH3(fijk: FaceIJK, res: Int): ULong {
        var h = H3_INIT
        h = setMode(h, 1uL) // H3_CELL_MODE
        h = setRes(h, res)

        if (res == 0) {
            if (fijk.coord[0] > 2 || fijk.coord[1] > 2 || fijk.coord[2] > 2) return 0uL
            return setBaseCell(h, faceIjkBaseCell(fijk.face, fijk.coord[0], fijk.coord[1], fijk.coord[2]))
        }

        // build the index digits from finest res up
        var ijk = fijk.coord.copyOf()
        for (r in res - 1 downTo 0) {
            val lastIJK = ijk.copyOf()
            val lastCenter: IntArray
            if (isResolutionClassIII(r + 1)) {
                upAp7(ijk)
                lastCenter = ijk.copyOf()
                downAp7(lastCenter)
            } else {
                upAp7r(ijk)
                lastCenter = ijk.copyOf()
                downAp7r(lastCenter)
            }
            val diff = intArrayOf(
                lastIJK[0] - lastCenter[0],
                lastIJK[1] - lastCenter[1],
                lastIJK[2] - lastCenter[2],
            )
            ijkNormalize(diff)
            h = setDigit(h, r + 1, unitIjkToDigit(diff))
        }

        if (ijk[0] > 2 || ijk[1] > 2 || ijk[2] > 2) return 0uL

        val baseCell = faceIjkBaseCell(fijk.face, ijk[0], ijk[1], ijk[2])
        h = setBaseCell(h, baseCell)
        val numRots = faceIjkBaseCellCcwRot60(fijk.face, ijk[0], ijk[1], ijk[2])

        if (isBaseCellPentagon(baseCell)) {
            // force rotation out of missing k-axes sub-sequence
            if (leadingNonZeroDigit(h) == K_AXES_DIGIT) {
                h = if (baseCellIsCwOffset(baseCell, fijk.face)) {
                    h3Rotate60cw(h)
                } else {
                    h3Rotate60ccw(h)
                }
            }
            repeat(numRots) { h = h3RotatePent60ccw(h) }
        } else {
            repeat(numRots) { h = h3Rotate60ccw(h) }
        }
        return h
    }

    // Neighbor traversal (algos.c)

    /** Directions used for traversing a hexagonal ring counterclockwise. */
    private val DISK_DIRECTIONS = intArrayOf(
        J_AXES_DIGIT, JK_AXES_DIGIT, K_AXES_DIGIT, IK_AXES_DIGIT, I_AXES_DIGIT, IJ_AXES_DIGIT,
    )

    // Current digit -> direction -> new digit (class II)
    private val NEW_DIGIT_II: Array<IntArray> = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6),
        intArrayOf(1, 4, 3, 6, 5, 2, 0),
        intArrayOf(2, 3, 1, 4, 6, 0, 5),
        intArrayOf(3, 6, 4, 5, 0, 1, 2),
        intArrayOf(4, 5, 6, 0, 2, 3, 1),
        intArrayOf(5, 2, 0, 1, 3, 6, 4),
        intArrayOf(6, 0, 5, 2, 1, 4, 3),
    )
    private val NEW_ADJUSTMENT_II: Array<IntArray> = arrayOf(
        intArrayOf(0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 1, 0, 1, 0, 5, 0),
        intArrayOf(0, 0, 2, 3, 0, 0, 2),
        intArrayOf(0, 1, 3, 3, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 4, 4, 6),
        intArrayOf(0, 5, 0, 0, 4, 5, 0),
        intArrayOf(0, 0, 2, 0, 6, 0, 6),
    )
    private val NEW_DIGIT_III: Array<IntArray> = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6),
        intArrayOf(1, 2, 3, 4, 5, 6, 0),
        intArrayOf(2, 3, 4, 5, 6, 0, 1),
        intArrayOf(3, 4, 5, 6, 0, 1, 2),
        intArrayOf(4, 5, 6, 0, 1, 2, 3),
        intArrayOf(5, 6, 0, 1, 2, 3, 4),
        intArrayOf(6, 0, 1, 2, 3, 4, 5),
    )
    private val NEW_ADJUSTMENT_III: Array<IntArray> = arrayOf(
        intArrayOf(0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 1, 0, 3, 0, 1, 0),
        intArrayOf(0, 0, 2, 2, 0, 0, 6),
        intArrayOf(0, 3, 2, 3, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 4, 5, 4),
        intArrayOf(0, 1, 0, 0, 5, 5, 0),
        intArrayOf(0, 0, 6, 0, 4, 0, 6),
    )

    private sealed class NeighborResult {
        class Success(val index: ULong) : NeighborResult()
        object Pentagon : NeighborResult()
        object Invalid : NeighborResult()
    }

    /** h3NeighborRotations from algos.c. */
    private fun h3NeighborRotations(origin: ULong, dirIn: Int, rotationsIn: Int): NeighborResult {
        var current = origin
        var dir = dirIn
        if (dir < CENTER_DIGIT || dir >= INVALID_DIGIT) return NeighborResult.Invalid

        var rotations = rotationsIn % 6
        repeat(rotations) { dir = rotate60ccw(dir) }

        var newRotations = 0
        val oldBaseCell = getBaseCell(current)
        if (oldBaseCell < 0 || oldBaseCell >= 122) return NeighborResult.Invalid
        val oldLeadingDigit = leadingNonZeroDigit(current)

        // Adjust the indexing digits and, if needed, the base cell.
        var r = getRes(current) - 1
        while (true) {
            if (r == -1) {
                current = setBaseCell(current, baseCellNeighbor(oldBaseCell, dir))
                newRotations = baseCellNeighborRot(oldBaseCell, dir)
                if (getBaseCell(current) == INVALID_BASE_CELL) {
                    // Adjust for the deleted k vertex at the base cell level.
                    current = setBaseCell(current, baseCellNeighbor(oldBaseCell, IK_AXES_DIGIT))
                    newRotations = baseCellNeighborRot(oldBaseCell, IK_AXES_DIGIT)
                    current = h3Rotate60ccw(current)
                    rotations += 1
                }
                break
            } else {
                val oldDigit = getDigit(current, r + 1)
                val nextDir: Int
                if (oldDigit == INVALID_DIGIT) {
                    return NeighborResult.Invalid
                } else if (isResolutionClassIII(r + 1)) {
                    current = setDigit(current, r + 1, NEW_DIGIT_II[oldDigit][dir])
                    nextDir = NEW_ADJUSTMENT_II[oldDigit][dir]
                } else {
                    current = setDigit(current, r + 1, NEW_DIGIT_III[oldDigit][dir])
                    nextDir = NEW_ADJUSTMENT_III[oldDigit][dir]
                }
                if (nextDir != CENTER_DIGIT) {
                    dir = nextDir
                    r -= 1
                } else {
                    break
                }
            }
        }

        val newBaseCell = getBaseCell(current)
        if (isBaseCellPentagon(newBaseCell)) {
            var alreadyAdjustedKSubsequence = false
            // force rotation out of missing k-axes sub-sequence
            if (leadingNonZeroDigit(current) == K_AXES_DIGIT) {
                if (oldBaseCell != newBaseCell) {
                    // traversed into the deleted k subsequence of a pentagon base cell
                    current = if (baseCellIsCwOffset(newBaseCell, baseCellHomeFace(oldBaseCell))) {
                        h3Rotate60cw(current)
                    } else {
                        h3Rotate60ccw(current)
                    }
                    alreadyAdjustedKSubsequence = true
                } else {
                    // traversed into the deleted k subsequence from within the same pentagon
                    when (oldLeadingDigit) {
                        CENTER_DIGIT -> return NeighborResult.Pentagon
                        JK_AXES_DIGIT -> {
                            current = h3Rotate60ccw(current)
                            rotations += 1
                        }
                        IK_AXES_DIGIT -> {
                            current = h3Rotate60cw(current)
                            rotations += 5
                        }
                        else -> return NeighborResult.Invalid
                    }
                }
            }
            repeat(newRotations) { current = h3RotatePent60ccw(current) }
            if (oldBaseCell != newBaseCell) {
                if (isBaseCellPolarPentagon(newBaseCell)) {
                    if (oldBaseCell != 118 && oldBaseCell != 8 &&
                        leadingNonZeroDigit(current) != JK_AXES_DIGIT
                    ) {
                        rotations += 1
                    }
                } else if (leadingNonZeroDigit(current) == IK_AXES_DIGIT && !alreadyAdjustedKSubsequence) {
                    rotations += 1
                }
            }
        } else {
            repeat(newRotations) { current = h3Rotate60ccw(current) }
        }

        return NeighborResult.Success(current)
    }
}
