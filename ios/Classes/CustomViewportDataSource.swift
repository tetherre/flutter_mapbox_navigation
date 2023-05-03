import MapboxMaps
import MapboxNavigation
import MapboxCoreNavigation

/**
 Custom implementation of Navigation Camera data source, which is used to fill and store
 `CameraOptions` which will be later used by `CustomCameraStateTransition` for execution of
 transitions and continuous camera updates.
 
 To be able to use custom camera data source user has to create instance of `CustomCameraStateTransition`
 and then override with it default implementation, by modifying
 `NavigationMapView.NavigationCamera.ViewportDataSource` or
 `NavigationViewController.NavigationMapView.NavigationCamera.ViewportDataSource` properties.
 
 By default Navigation SDK for iOS provides default implementation of `ViewportDataSource`
 in `NavigationViewportDataSource`.
 */
class CustomViewportDataSource: ViewportDataSource {
    
    public weak var delegate: ViewportDataSourceDelegate?
    
    public var followingMobileCamera: CameraOptions = CameraOptions()
    
    public var followingCarPlayCamera: CameraOptions = CameraOptions()

    public var overviewMobileCamera: CameraOptions = CameraOptions()
    
    public var overviewCarPlayCamera: CameraOptions = CameraOptions()
    
    weak var mapView: MapView?
    
    // MARK: - Initializer methods
    
    public required init(_ mapView: MapView) {
        self.mapView = mapView
        self.mapView?.location.addLocationConsumer(newConsumer: self)
        
        subscribeForNotifications()
    }
    
    deinit {
        unsubscribeFromNotifications()
    }
    
    // MARK: - Notifications observer methods
    
    func subscribeForNotifications() {
        // `CustomViewportDataSource` uses raw locations provided by `LocationConsumer` in
        // free-drive mode and locations snapped to the road provided by
        // `Notification.Name.routeControllerProgressDidChange` notification.
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(progressDidChange(_:)),
                                               name: .routeControllerProgressDidChange,
                                               object: nil)
    }
    
    func unsubscribeFromNotifications() {
        NotificationCenter.default.removeObserver(self,
                                                  name: .routeControllerProgressDidChange,
                                                  object: nil)
    }
    
    @objc func progressDidChange(_ notification: NSNotification) {
        let location = notification.userInfo?[RouteController.NotificationUserInfoKey.locationKey] as? CLLocation
        let routeProgress = notification.userInfo?[RouteController.NotificationUserInfoKey.routeProgressKey] as? RouteProgress
        let cameraOptions = self.cameraOptions(location, routeProgress: routeProgress)
        
        delegate?.viewportDataSource(self, didUpdate: cameraOptions)
    }
    
    func cameraOptions(_ location: CLLocation?, routeProgress: RouteProgress? = nil) -> [String: CameraOptions] {
        followingMobileCamera.center = location?.coordinate
        // Set the bearing of the `MapView` (measured in degrees clockwise from true north).
        followingMobileCamera.bearing = .zero
        followingMobileCamera.padding = .zero
        followingMobileCamera.zoom = 15.5
        followingMobileCamera.pitch = 45.0
        
        if let shape = routeProgress?.route.shape,
           let camera = mapView?.mapboxMap.camera(for: .lineString(shape),
                                                  padding: UIEdgeInsets(top: 150.0, left: 10.0, bottom: 150.0, right: 10.0),
                                                  bearing: 0.0,
                                                  pitch: 0.0) {
            overviewMobileCamera = camera
        }
        
        let cameraOptions = [
            CameraOptions.followingMobileCamera: followingMobileCamera,
            CameraOptions.overviewMobileCamera: overviewMobileCamera
        ]
        
        return cameraOptions
    }
}

// MARK: - LocationConsumer delegate

extension CustomViewportDataSource: LocationConsumer {
    
    var shouldTrackLocation: Bool {
        return true
    }

    public func locationUpdate(newLocation: MapboxMaps.Location) {
        let location = CLLocation(coordinate: newLocation.coordinate,
                                  altitude: newLocation.location.altitude,
                                  horizontalAccuracy: newLocation.horizontalAccuracy,
                                  verticalAccuracy: newLocation.location.verticalAccuracy,
                                  course: newLocation.course,
                                  speed: newLocation.location.speed,
                                  timestamp: Date())
        
        let cameraOptions = self.cameraOptions(newLocation.location)
        delegate?.viewportDataSource(self, didUpdate: cameraOptions)
    }
}

class CustomCameraStateTransition: CameraStateTransition {
    
    weak var mapView: MapView?
    
    required init(_ mapView: MapView) {
        self.mapView = mapView
    }
    
    func transitionToFollowing(_ cameraOptions: CameraOptions, completion: @escaping (() -> Void)) {
        mapView?.camera.ease(to: cameraOptions, duration: 0.3, curve: .easeInOut, completion: { _ in
            completion()
        })
    }
    
    func transitionToOverview(_ cameraOptions: CameraOptions, completion: @escaping (() -> Void)) {
        mapView?.camera.ease(to: cameraOptions, duration: 0.3, curve: .easeInOut, completion: { _ in
            completion()
        })
    }
    
    func update(to cameraOptions: CameraOptions, state: NavigationCameraState) {
        mapView?.camera.ease(to: cameraOptions, duration: 0.3, curve: .easeInOut, completion: nil)
    }
    
    func cancelPendingTransition() {
        mapView?.camera.cancelAnimations()
    }
}
