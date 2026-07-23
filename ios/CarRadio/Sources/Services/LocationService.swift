// LocationService.swift
// CLLocationManager wrapper: 2 s cadence, background updates, GpsState output.
// Heading = GPS course (degrees from true north); falls back to the last good
// course when the fix has no course (course < 0 at low speed).

import Foundation
import CoreLocation

@MainActor
final class LocationService: NSObject, CLLocationManagerDelegate {
    /// Fired at most every `Constants.locationCadence` seconds.
    var onFix: ((GpsState) -> Void)?
    var onAuthorizationChange: ((CLAuthorizationStatus) -> Void)?

    private let manager = CLLocationManager()
    private var lastEmit: Date = .distantPast
    private var lastGoodCourse: Double = 0

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .automotiveNavigation
        manager.pausesLocationUpdatesAutomatically = false
    }

    func requestPermissionAndStart() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            start()
        default:
            break
        }
    }

    func start() {
        // Background mode "location" is declared in Info.plist; only enable
        // background updates when the entitlement/authorization allows it.
        if manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse {
            manager.allowsBackgroundLocationUpdates = true
        }
        manager.startUpdatingLocation()
    }

    func stop() {
        manager.stopUpdatingLocation()
    }

    // MARK: CLLocationManagerDelegate

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.onAuthorizationChange?(status)
            if status == .authorizedWhenInUse || status == .authorizedAlways {
                self.start()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        Task { @MainActor in
            self.handle(location)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        NSLog("CarRadio location error: \(error.localizedDescription)")
    }

    private func handle(_ location: CLLocation) {
        let now = Date()
        guard now.timeIntervalSince(lastEmit) >= Constants.locationCadence else { return }
        lastEmit = now

        if location.course >= 0 {
            lastGoodCourse = location.course
        }
        let speed = max(0, location.speed) // m/s; -1 when invalid
        let fix = GpsState(
            lat: location.coordinate.latitude,
            lng: location.coordinate.longitude,
            heading: lastGoodCourse,
            speed: speed
        )
        onFix?(fix)
    }
}
