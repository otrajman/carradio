// H3Lite.swift
// Minimal pure-Swift port of Uber H3 v4.1.0 covering exactly what Car Radio needs:
//   - latLngToCell(lat:lng:res:)  (geo -> cell, any res 0-15; we use 7/8/9)
//   - gridDisk(_:k:)              (k-ring neighborhood, pentagon-safe)
//
// Ported faithfully from the H3 C source (faceijk.c, coordijk.c, h3Index.c,
// baseCells.c, algos.c). Lookup tables live in H3LiteTables.swift and were
// mechanically extracted from the same source — see tools note in that file.
//
// Output is the canonical lowercase-hex form identical to h3-js
// (e.g. "872830828ffffff"). A JavaScript twin of this file was validated
// against official h3-js v4 on 23,872 vectors (random global points at
// res 0-15, polar/pentagon stress points, and gridDisk k=1..3 including all
// res 7-9 pentagons); the Swift port passes the same fixture suite in
// H3LiteTests. Re-verify against h3-js before shipping if either file is
// edited (see ios/README.md).
//
// No UIKit. Foundation only.

import Foundation

public enum H3Lite {
    // MARK: - Constants

    private static let epsilon = 1e-16
    private static let m2Pi = 2.0 * Double.pi
    private static let mSin60 = 0.8660254037844386467637231707529361834714
    private static let mAp7RotRads = 0.333473172251832115336090755351601070065900389
    private static let res0UGnomonic = 0.38196601125010500003
    private static let mSqrt7 = 2.6457513110645905905016157536392604257102
    private static let maxH3Res = 15
    private static let invalidBaseCell = 127

    // Direction digits (Direction enum in the C source)
    private static let centerDigit = 0
    private static let kAxesDigit = 1
    private static let jAxesDigit = 2
    private static let jkAxesDigit = 3
    private static let iAxesDigit = 4
    private static let ikAxesDigit = 5
    private static let ijAxesDigit = 6
    private static let invalidDigit = 7

    private static let unitVecs: [[Int]] = [
        [0, 0, 0], [0, 0, 1], [0, 1, 0], [0, 1, 1], [1, 0, 0], [1, 0, 1], [1, 1, 0],
    ]

    // MARK: - Public API

    /// Returns the H3 cell containing the given WGS84 coordinate at `res`,
    /// as a canonical lowercase hex string (identical to h3-js).
    /// Returns nil only for invalid input (res out of range / non-finite coords).
    public static func latLngToCell(lat: Double, lng: Double, res: Int) -> String? {
        guard res >= 0, res <= maxH3Res, lat.isFinite, lng.isFinite else { return nil }
        let fijk = geoToFaceIjk(latRads: lat * .pi / 180.0, lngRads: lng * .pi / 180.0, res: res)
        let h = faceIjkToH3(fijk, res: res)
        guard h != 0 else { return nil }
        return String(h, radix: 16)
    }

    /// Returns all cells within grid distance `k` of `cell` (including `cell`).
    /// Pentagon-safe (uses the "safe" BFS traversal from algos.c).
    /// Ordering is deterministic (origin first, then BFS discovery order) but is
    /// NOT guaranteed to match h3-js spiral ordering — treat the result as a set.
    /// Returns [cell] unchanged if the input cannot be parsed.
    public static func gridDisk(_ cell: String, k: Int) -> [String] {
        guard k >= 0, let origin = UInt64(cell, radix: 16), origin != 0 else { return [cell] }
        var distances: [UInt64: Int] = [:]
        var order: [UInt64] = []
        var stack: [(UInt64, Int)] = [(origin, 0)]
        while let (current, d) = stack.popLast() {
            if let prev = distances[current], prev <= d { continue }
            if distances[current] == nil { order.append(current) }
            distances[current] = d
            if d >= k { continue }
            for dir in Self.diskDirections {
                var rotations = 0
                switch h3NeighborRotations(origin: current, dir: dir, rotations: &rotations) {
                case .success(let neighbor):
                    stack.append((neighbor, d + 1))
                case .pentagon:
                    continue // expected when traversing off a pentagon
                case .invalid:
                    return [cell] // invalid input index
                }
            }
        }
        return order.map { String($0, radix: 16) }
    }

    // MARK: - Angle helpers

    private static func posAngleRads(_ rads: Double) -> Double {
        var tmp = rads < 0.0 ? rads + m2Pi : rads
        if rads >= m2Pi { tmp -= m2Pi }
        return tmp
    }

    private static func geoAzimuthRads(p1Lat: Double, p1Lng: Double, p2Lat: Double, p2Lng: Double) -> Double {
        atan2(
            cos(p2Lat) * sin(p2Lng - p1Lng),
            cos(p1Lat) * sin(p2Lat) - sin(p1Lat) * cos(p2Lat) * cos(p2Lng - p1Lng)
        )
    }

    private static func isResolutionClassIII(_ res: Int) -> Bool { res % 2 == 1 }

    // MARK: - IJK coordinates (coordijk.c)

    private static func ijkNormalize(_ c: inout [Int]) {
        if c[0] < 0 { c[1] -= c[0]; c[2] -= c[0]; c[0] = 0 }
        if c[1] < 0 { c[0] -= c[1]; c[2] -= c[1]; c[1] = 0 }
        if c[2] < 0 { c[0] -= c[2]; c[1] -= c[2]; c[2] = 0 }
        let minVal = min(c[0], c[1], c[2])
        if minVal > 0 { c[0] -= minVal; c[1] -= minVal; c[2] -= minVal }
    }

    /// _hex2dToCoordIJK from coordijk.c (DGGRID quantization).
    private static func hex2dToCoordIJK(x: Double, y: Double) -> [Int] {
        var h = [0, 0, 0]
        let a1 = abs(x)
        let a2 = abs(y)

        let x2 = a2 / mSin60
        let x1 = a1 + x2 / 2.0

        let m1 = Int(x1.rounded(.down))
        let m2 = Int(x2.rounded(.down))

        let r1 = x1 - Double(m1)
        let r2 = x2 - Double(m2)

        if r1 < 0.5 {
            if r1 < 1.0 / 3.0 {
                if r2 < (1.0 + r1) / 2.0 {
                    h[0] = m1; h[1] = m2
                } else {
                    h[0] = m1; h[1] = m2 + 1
                }
            } else {
                h[1] = r2 < (1.0 - r1) ? m2 : m2 + 1
                h[0] = ((1.0 - r1) <= r2 && r2 < (2.0 * r1)) ? m1 + 1 : m1
            }
        } else {
            if r1 < 2.0 / 3.0 {
                h[1] = r2 < (1.0 - r1) ? m2 : m2 + 1
                h[0] = ((2.0 * r1 - 1.0) < r2 && r2 < (1.0 - r1)) ? m1 : m1 + 1
            } else {
                if r2 < (r1 / 2.0) {
                    h[0] = m1 + 1; h[1] = m2
                } else {
                    h[0] = m1 + 1; h[1] = m2 + 1
                }
            }
        }

        // fold across the axes if necessary
        if x < 0.0 {
            if h[1] % 2 == 0 { // even
                let axisi = h[1] / 2
                let diff = h[0] - axisi
                h[0] = h[0] - 2 * diff
            } else {
                let axisi = (h[1] + 1) / 2
                let diff = h[0] - axisi
                h[0] = h[0] - (2 * diff + 1)
            }
        }
        if y < 0.0 {
            // C: h->i = h->i - (2 * h->j + 1) / 2  (integer division, j >= 0 here)
            h[0] = h[0] - (2 * h[1] + 1) / 2
            h[1] = -h[1]
        }
        ijkNormalize(&h)
        return h
    }

    private static func upAp7(_ c: inout [Int]) {
        let i = c[0] - c[2]
        let j = c[1] - c[2]
        c[0] = Int((Double(3 * i - j) / 7.0).rounded())
        c[1] = Int((Double(i + 2 * j) / 7.0).rounded())
        c[2] = 0
        ijkNormalize(&c)
    }

    private static func upAp7r(_ c: inout [Int]) {
        let i = c[0] - c[2]
        let j = c[1] - c[2]
        c[0] = Int((Double(2 * i + j) / 7.0).rounded())
        c[1] = Int((Double(3 * j - i) / 7.0).rounded())
        c[2] = 0
        ijkNormalize(&c)
    }

    private static func downAp7(_ c: inout [Int]) {
        // res r unit vectors in res r+1: i={3,0,1} j={1,3,0} k={0,1,3}
        let i = c[0], j = c[1], k = c[2]
        c[0] = 3 * i + j
        c[1] = 3 * j + k
        c[2] = i + 3 * k
        ijkNormalize(&c)
    }

    private static func downAp7r(_ c: inout [Int]) {
        // res r unit vectors in res r+1: i={3,1,0} j={0,3,1} k={1,0,3}
        let i = c[0], j = c[1], k = c[2]
        c[0] = 3 * i + k
        c[1] = i + 3 * j
        c[2] = j + 3 * k
        ijkNormalize(&c)
    }

    private static func unitIjkToDigit(_ ijk: [Int]) -> Int {
        var c = ijk
        ijkNormalize(&c)
        for digit in 0..<7 where c == unitVecs[digit] {
            return digit
        }
        return invalidDigit
    }

    private static func rotate60ccw(_ digit: Int) -> Int {
        switch digit {
        case kAxesDigit: return ikAxesDigit
        case ikAxesDigit: return iAxesDigit
        case iAxesDigit: return ijAxesDigit
        case ijAxesDigit: return jAxesDigit
        case jAxesDigit: return jkAxesDigit
        case jkAxesDigit: return kAxesDigit
        default: return digit
        }
    }

    private static func rotate60cw(_ digit: Int) -> Int {
        switch digit {
        case kAxesDigit: return jkAxesDigit
        case jkAxesDigit: return jAxesDigit
        case jAxesDigit: return ijAxesDigit
        case ijAxesDigit: return iAxesDigit
        case iAxesDigit: return ikAxesDigit
        case ikAxesDigit: return kAxesDigit
        default: return digit
        }
    }

    // MARK: - Table accessors (baseCells.c)

    private static func faceIjkBaseCellEntry(face: Int, i: Int, j: Int, k: Int) -> (baseCell: Int, ccwRot60: Int) {
        let idx = (((face * 3 + i) * 3 + j) * 3 + k) * 2
        return (Int(H3Tables.faceIjkBaseCells[idx]), Int(H3Tables.faceIjkBaseCells[idx + 1]))
    }

    private static func isBaseCellPentagon(_ baseCell: Int) -> Bool {
        guard baseCell >= 0, baseCell < 122 else { return false }
        return H3Tables.baseCellData[baseCell * 7 + 4] == 1
    }

    private static func isBaseCellPolarPentagon(_ baseCell: Int) -> Bool {
        baseCell == 4 || baseCell == 117
    }

    private static func baseCellHomeFace(_ baseCell: Int) -> Int {
        Int(H3Tables.baseCellData[baseCell * 7])
    }

    private static func baseCellIsCwOffset(_ baseCell: Int, testFace: Int) -> Bool {
        Int(H3Tables.baseCellData[baseCell * 7 + 5]) == testFace
            || Int(H3Tables.baseCellData[baseCell * 7 + 6]) == testFace
    }

    private static func baseCellNeighbor(_ baseCell: Int, dir: Int) -> Int {
        Int(H3Tables.baseCellNeighbors[baseCell * 7 + dir])
    }

    private static func baseCellNeighborRot(_ baseCell: Int, dir: Int) -> Int {
        Int(H3Tables.baseCellNeighbor60CCWRots[baseCell * 7 + dir])
    }

    // MARK: - H3 index bit layout (h3Index.h)

    private static let h3Init: UInt64 = 35_184_372_088_831 // all digits set to 7
    private static let modeOffset: UInt64 = 59
    private static let baseCellOffset: UInt64 = 45
    private static let resOffset: UInt64 = 52

    private static func setMode(_ h: UInt64, _ mode: UInt64) -> UInt64 {
        (h & ~(UInt64(15) << modeOffset)) | (mode << modeOffset)
    }

    private static func setRes(_ h: UInt64, _ res: Int) -> UInt64 {
        (h & ~(UInt64(15) << resOffset)) | (UInt64(res) << resOffset)
    }

    private static func getRes(_ h: UInt64) -> Int {
        Int((h >> resOffset) & 15)
    }

    private static func setBaseCell(_ h: UInt64, _ baseCell: Int) -> UInt64 {
        (h & ~(UInt64(127) << baseCellOffset)) | (UInt64(baseCell) << baseCellOffset)
    }

    private static func getBaseCell(_ h: UInt64) -> Int {
        Int((h >> baseCellOffset) & 127)
    }

    private static func digitShift(_ res: Int) -> UInt64 {
        UInt64((maxH3Res - res) * 3)
    }

    private static func getDigit(_ h: UInt64, _ res: Int) -> Int {
        Int((h >> digitShift(res)) & 7)
    }

    private static func setDigit(_ h: UInt64, _ res: Int, _ digit: Int) -> UInt64 {
        let shift = digitShift(res)
        return (h & ~(UInt64(7) << shift)) | (UInt64(digit) << shift)
    }

    private static func leadingNonZeroDigit(_ h: UInt64) -> Int {
        let res = getRes(h)
        for r in stride(from: 1, through: res, by: 1) {
            let d = getDigit(h, r)
            if d != 0 { return d }
        }
        return centerDigit
    }

    private static func h3Rotate60ccw(_ h: UInt64) -> UInt64 {
        var h = h
        let res = getRes(h)
        for r in stride(from: 1, through: res, by: 1) {
            h = setDigit(h, r, rotate60ccw(getDigit(h, r)))
        }
        return h
    }

    private static func h3Rotate60cw(_ h: UInt64) -> UInt64 {
        var h = h
        let res = getRes(h)
        for r in stride(from: 1, through: res, by: 1) {
            h = setDigit(h, r, rotate60cw(getDigit(h, r)))
        }
        return h
    }

    private static func h3RotatePent60ccw(_ h: UInt64) -> UInt64 {
        var h = h
        var foundFirstNonZeroDigit = false
        let res = getRes(h)
        for r in stride(from: 1, through: res, by: 1) {
            h = setDigit(h, r, rotate60ccw(getDigit(h, r)))
            if !foundFirstNonZeroDigit, getDigit(h, r) != 0 {
                foundFirstNonZeroDigit = true
                if leadingNonZeroDigit(h) == kAxesDigit {
                    h = h3Rotate60ccw(h)
                }
            }
        }
        return h
    }

    // MARK: - geo -> FaceIJK (faceijk.c)

    private struct FaceIJK {
        var face: Int
        var coord: [Int]
    }

    private static func geoToFaceIjk(latRads: Double, lngRads: Double, res: Int) -> FaceIJK {
        // _geoToClosestFace: closest icosahedron face by squared chord distance
        let cosLat = cos(latRads)
        let px = cos(lngRads) * cosLat
        let py = sin(lngRads) * cosLat
        let pz = sin(latRads)

        var face = 0
        var sqd = 5.0
        for f in 0..<20 {
            let fx = H3Tables.faceCenterPoint[f * 3]
            let fy = H3Tables.faceCenterPoint[f * 3 + 1]
            let fz = H3Tables.faceCenterPoint[f * 3 + 2]
            let d = (fx - px) * (fx - px) + (fy - py) * (fy - py) + (fz - pz) * (fz - pz)
            if d < sqd {
                face = f
                sqd = d
            }
        }

        // _geoToHex2d
        var r = acos(1 - sqd / 2)
        var vx = 0.0
        var vy = 0.0
        if r >= epsilon {
            let fcLat = H3Tables.faceCenterGeo[face * 2]
            let fcLng = H3Tables.faceCenterGeo[face * 2 + 1]
            var theta = posAngleRads(
                H3Tables.faceAxesAzRadsCII0[face]
                    - posAngleRads(geoAzimuthRads(p1Lat: fcLat, p1Lng: fcLng, p2Lat: latRads, p2Lng: lngRads))
            )
            if isResolutionClassIII(res) {
                theta = posAngleRads(theta - mAp7RotRads)
            }
            r = tan(r)
            r /= res0UGnomonic
            for _ in 0..<res { r *= mSqrt7 }
            vx = r * cos(theta)
            vy = r * sin(theta)
        }
        return FaceIJK(face: face, coord: hex2dToCoordIJK(x: vx, y: vy))
    }

    // MARK: - FaceIJK -> H3 (h3Index.c _faceIjkToH3)

    private static func faceIjkToH3(_ fijk: FaceIJK, res: Int) -> UInt64 {
        var h = h3Init
        h = setMode(h, 1) // H3_CELL_MODE
        h = setRes(h, res)

        if res == 0 {
            if fijk.coord[0] > 2 || fijk.coord[1] > 2 || fijk.coord[2] > 2 { return 0 }
            let entry = faceIjkBaseCellEntry(face: fijk.face, i: fijk.coord[0], j: fijk.coord[1], k: fijk.coord[2])
            return setBaseCell(h, entry.baseCell)
        }

        // build the index digits from finest res up
        var ijk = fijk.coord
        for r in stride(from: res - 1, through: 0, by: -1) {
            let lastIJK = ijk
            var lastCenter: [Int]
            if isResolutionClassIII(r + 1) {
                upAp7(&ijk)
                lastCenter = ijk
                downAp7(&lastCenter)
            } else {
                upAp7r(&ijk)
                lastCenter = ijk
                downAp7r(&lastCenter)
            }
            var diff = [lastIJK[0] - lastCenter[0], lastIJK[1] - lastCenter[1], lastIJK[2] - lastCenter[2]]
            ijkNormalize(&diff)
            h = setDigit(h, r + 1, unitIjkToDigit(diff))
        }

        if ijk[0] > 2 || ijk[1] > 2 || ijk[2] > 2 { return 0 }

        let entry = faceIjkBaseCellEntry(face: fijk.face, i: ijk[0], j: ijk[1], k: ijk[2])
        let baseCell = entry.baseCell
        h = setBaseCell(h, baseCell)
        let numRots = entry.ccwRot60

        if isBaseCellPentagon(baseCell) {
            // force rotation out of missing k-axes sub-sequence
            if leadingNonZeroDigit(h) == kAxesDigit {
                if baseCellIsCwOffset(baseCell, testFace: fijk.face) {
                    h = h3Rotate60cw(h)
                } else {
                    h = h3Rotate60ccw(h)
                }
            }
            for _ in 0..<numRots { h = h3RotatePent60ccw(h) }
        } else {
            for _ in 0..<numRots { h = h3Rotate60ccw(h) }
        }
        return h
    }

    // MARK: - Neighbor traversal (algos.c)

    /// Directions used for traversing a hexagonal ring counterclockwise.
    private static let diskDirections = [jAxesDigit, jkAxesDigit, kAxesDigit, ikAxesDigit, iAxesDigit, ijAxesDigit]

    // Current digit -> direction -> new digit (class II)
    private static let newDigitII: [[Int]] = [
        [0, 1, 2, 3, 4, 5, 6],
        [1, 4, 3, 6, 5, 2, 0],
        [2, 3, 1, 4, 6, 0, 5],
        [3, 6, 4, 5, 0, 1, 2],
        [4, 5, 6, 0, 2, 3, 1],
        [5, 2, 0, 1, 3, 6, 4],
        [6, 0, 5, 2, 1, 4, 3],
    ]
    private static let newAdjustmentII: [[Int]] = [
        [0, 0, 0, 0, 0, 0, 0],
        [0, 1, 0, 1, 0, 5, 0],
        [0, 0, 2, 3, 0, 0, 2],
        [0, 1, 3, 3, 0, 0, 0],
        [0, 0, 0, 0, 4, 4, 6],
        [0, 5, 0, 0, 4, 5, 0],
        [0, 0, 2, 0, 6, 0, 6],
    ]
    private static let newDigitIII: [[Int]] = [
        [0, 1, 2, 3, 4, 5, 6],
        [1, 2, 3, 4, 5, 6, 0],
        [2, 3, 4, 5, 6, 0, 1],
        [3, 4, 5, 6, 0, 1, 2],
        [4, 5, 6, 0, 1, 2, 3],
        [5, 6, 0, 1, 2, 3, 4],
        [6, 0, 1, 2, 3, 4, 5],
    ]
    private static let newAdjustmentIII: [[Int]] = [
        [0, 0, 0, 0, 0, 0, 0],
        [0, 1, 0, 3, 0, 1, 0],
        [0, 0, 2, 2, 0, 0, 6],
        [0, 3, 2, 3, 0, 0, 0],
        [0, 0, 0, 0, 4, 5, 4],
        [0, 1, 0, 0, 5, 5, 0],
        [0, 0, 6, 0, 4, 0, 6],
    ]

    private enum NeighborResult {
        case success(UInt64)
        case pentagon
        case invalid
    }

    /// h3NeighborRotations from algos.c.
    private static func h3NeighborRotations(origin: UInt64, dir dirIn: Int, rotations: inout Int) -> NeighborResult {
        var current = origin
        var dir = dirIn
        guard dir >= centerDigit, dir < invalidDigit else { return .invalid }

        rotations = rotations % 6
        for _ in 0..<rotations { dir = rotate60ccw(dir) }

        var newRotations = 0
        let oldBaseCell = getBaseCell(current)
        guard oldBaseCell >= 0, oldBaseCell < 122 else { return .invalid }
        let oldLeadingDigit = leadingNonZeroDigit(current)

        // Adjust the indexing digits and, if needed, the base cell.
        var r = getRes(current) - 1
        while true {
            if r == -1 {
                current = setBaseCell(current, baseCellNeighbor(oldBaseCell, dir: dir))
                newRotations = baseCellNeighborRot(oldBaseCell, dir: dir)
                if getBaseCell(current) == invalidBaseCell {
                    // Adjust for the deleted k vertex at the base cell level.
                    current = setBaseCell(current, baseCellNeighbor(oldBaseCell, dir: ikAxesDigit))
                    newRotations = baseCellNeighborRot(oldBaseCell, dir: ikAxesDigit)
                    current = h3Rotate60ccw(current)
                    rotations += 1
                }
                break
            } else {
                let oldDigit = getDigit(current, r + 1)
                let nextDir: Int
                if oldDigit == invalidDigit {
                    return .invalid
                } else if isResolutionClassIII(r + 1) {
                    current = setDigit(current, r + 1, newDigitII[oldDigit][dir])
                    nextDir = newAdjustmentII[oldDigit][dir]
                } else {
                    current = setDigit(current, r + 1, newDigitIII[oldDigit][dir])
                    nextDir = newAdjustmentIII[oldDigit][dir]
                }
                if nextDir != centerDigit {
                    dir = nextDir
                    r -= 1
                } else {
                    break
                }
            }
        }

        let newBaseCell = getBaseCell(current)
        if isBaseCellPentagon(newBaseCell) {
            var alreadyAdjustedKSubsequence = false
            // force rotation out of missing k-axes sub-sequence
            if leadingNonZeroDigit(current) == kAxesDigit {
                if oldBaseCell != newBaseCell {
                    // traversed into the deleted k subsequence of a pentagon base cell
                    if baseCellIsCwOffset(newBaseCell, testFace: baseCellHomeFace(oldBaseCell)) {
                        current = h3Rotate60cw(current)
                    } else {
                        current = h3Rotate60ccw(current)
                    }
                    alreadyAdjustedKSubsequence = true
                } else {
                    // traversed into the deleted k subsequence from within the same pentagon
                    switch oldLeadingDigit {
                    case centerDigit:
                        return .pentagon
                    case jkAxesDigit:
                        current = h3Rotate60ccw(current)
                        rotations += 1
                    case ikAxesDigit:
                        current = h3Rotate60cw(current)
                        rotations += 5
                    default:
                        return .invalid
                    }
                }
            }
            for _ in 0..<newRotations { current = h3RotatePent60ccw(current) }
            if oldBaseCell != newBaseCell {
                if isBaseCellPolarPentagon(newBaseCell) {
                    if oldBaseCell != 118, oldBaseCell != 8, leadingNonZeroDigit(current) != jkAxesDigit {
                        rotations += 1
                    }
                } else if leadingNonZeroDigit(current) == ikAxesDigit, !alreadyAdjustedKSubsequence {
                    rotations += 1
                }
            }
        } else {
            for _ in 0..<newRotations { current = h3Rotate60ccw(current) }
        }

        rotations = (rotations + newRotations) % 6
        return .success(current)
    }
}
