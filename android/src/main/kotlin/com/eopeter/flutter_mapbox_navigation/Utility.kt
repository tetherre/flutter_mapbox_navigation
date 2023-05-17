package com.eopeter.flutter_mapbox_navigation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.doOnAttach
import androidx.lifecycle.*
import com.eopeter.flutter_mapbox_navigation.activity.WaypointsSet
import eopeter.flutter_mapbox_navigation.databinding.NavigationActivityBinding
import eopeter.flutter_mapbox_navigation.R
import com.eopeter.flutter_mapbox_navigation.models.MapBoxEvents
import com.eopeter.flutter_mapbox_navigation.models.MapBoxRouteProgressEvent
import com.eopeter.flutter_mapbox_navigation.utilities.PluginUtilities
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.Bearing
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions

import com.mapbox.bindgen.Expected
import com.mapbox.geojson.Point
import com.mapbox.maps.*
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.TimeFormat
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.*
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.lifecycle.requireMapboxNavigation
import com.mapbox.navigation.core.replay.MapboxReplayer
import com.mapbox.navigation.core.replay.ReplayLocationEngine
import com.mapbox.navigation.core.replay.route.ReplayProgressObserver
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import com.mapbox.navigation.ui.base.util.MapboxNavigationConsumer
import com.mapbox.navigation.ui.maneuver.api.MapboxManeuverApi
import com.mapbox.navigation.ui.maneuver.view.MapboxManeuverView
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.camera.transition.NavigationCameraTransitionOptions
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLine
import com.mapbox.navigation.ui.tripprogress.api.MapboxTripProgressApi
import com.mapbox.navigation.ui.tripprogress.model.DistanceRemainingFormatter
import com.mapbox.navigation.ui.tripprogress.model.EstimatedTimeToArrivalFormatter
import com.mapbox.navigation.ui.tripprogress.model.PercentDistanceTraveledFormatter
import com.mapbox.navigation.ui.tripprogress.model.TimeRemainingFormatter
import com.mapbox.navigation.ui.tripprogress.model.TripProgressUpdateFormatter
import com.mapbox.navigation.ui.tripprogress.view.MapboxTripProgressView
import com.mapbox.navigation.ui.voice.api.MapboxSpeechApi
import com.mapbox.navigation.ui.voice.api.MapboxVoiceInstructionsPlayer
import com.mapbox.navigation.ui.voice.model.SpeechAnnouncement
import com.mapbox.navigation.ui.voice.model.SpeechError
import com.mapbox.navigation.ui.voice.model.SpeechValue
import com.mapbox.navigation.ui.voice.model.SpeechVolume

import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import timber.log.Timber
import java.util.*

open class TurnByTurn(ctx: Context, act: Activity, bind: NavigationActivityBinding, accessToken: String):  MethodChannel.MethodCallHandler, EventChannel.StreamHandler,
    Application.ActivityLifecycleCallbacks, LifecycleOwner {

    private var lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    open fun initFlutterChannelHandlers() {
        methodChannel?.setMethodCallHandler(this)
        eventChannel?.setStreamHandler(this)
    }

    @SuppressLint("MissingPermission")
    open fun initNavigation() {
        // initialize Mapbox Navigation
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        if (!MapboxNavigationApp.isSetup()) {
            MapboxNavigationApp.setup(
                NavigationOptions.Builder(this.context)
                    .accessToken(token)
                    // comment out the location engine setting block to disable simulation
                    //.locationEngine(replayLocationEngine)
                    .build()
            ).attach(this)
        }

        // initialize navigation trip observers
        registerObservers()

        binding.mapView.location.apply {
            setLocationProvider(navigationLocationProvider)

            // Uncomment this block of code if you want to see a circular puck with arrow.
            locationPuck = LocationPuck2D(
                bearingImage = ContextCompat.getDrawable(
                    context,
                    R.drawable.mapbox_navigation_puck_icon
                )
            )
            // When true, the blue circular puck is shown on the map. If set to false, user
            // location in the form of puck will not be shown on the map.
            enabled = true
        }

        ViewTreeLifecycleOwner.set(binding.mapView, this);
        mapboxMap = binding.mapView.getMapboxMap()

        // initialize Navigation Camera
        viewportDataSource = MapboxNavigationViewportDataSource(mapboxMap)
        navigationCamera = NavigationCamera(
            mapboxMap,
            binding.mapView.camera,
            viewportDataSource
        )
        // set the animations lifecycle listener to ensure the NavigationCamera stops
        // automatically following the user location when the map is interacted with
        binding.mapView.camera.addCameraAnimationsLifecycleListener(
            NavigationBasicGesturesHandler(navigationCamera)
        )
        navigationCamera.registerNavigationCameraStateChangeObserver { navigationCameraState ->
            // shows/hide the recenter button depending on the camera state
            when (navigationCameraState) {
                NavigationCameraState.TRANSITION_TO_FOLLOWING,
                NavigationCameraState.FOLLOWING -> binding.recenter.visibility = View.INVISIBLE
                NavigationCameraState.TRANSITION_TO_OVERVIEW,
                NavigationCameraState.OVERVIEW,
                NavigationCameraState.IDLE -> binding.recenter.visibility = View.VISIBLE
            }
        }
        // set the padding values depending on screen orientation and visible view layout
        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            viewportDataSource.overviewPadding = landscapeOverviewPadding
        } else {
            viewportDataSource.overviewPadding = overviewPadding
        }
        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            viewportDataSource.followingPadding = landscapeFollowingPadding
        } else {
            viewportDataSource.followingPadding = followingPadding
        }

        // make sure to use the same DistanceFormatterOptions across different features
        val distanceFormatterOptions = DistanceFormatterOptions.Builder(activity).build()

        // initialize maneuver api that feeds the data to the top banner maneuver view
        maneuverApi = MapboxManeuverApi(
            MapboxDistanceFormatter(distanceFormatterOptions)
        )

        // initialize bottom progress view
        tripProgressApi = MapboxTripProgressApi(
            TripProgressUpdateFormatter.Builder(activity)
                .distanceRemainingFormatter(
                    DistanceRemainingFormatter(distanceFormatterOptions)
                )
                .timeRemainingFormatter(
                    TimeRemainingFormatter(activity)
                )
                .percentRouteTraveledFormatter(
                    PercentDistanceTraveledFormatter()
                )
                .estimatedTimeToArrivalFormatter(
                    EstimatedTimeToArrivalFormatter(activity, TimeFormat.NONE_SPECIFIED)
                )
                .build()
        )

        // initialize voice instructions api and the voice instruction player
        speechApi = MapboxSpeechApi(
            activity,
            token,
            Locale.US.language
        )
        voiceInstructionsPlayer = MapboxVoiceInstructionsPlayer(
            activity,
            Locale.US.language
        )

        // initialize route line, the withRouteLineBelowLayerId is specified to place
        // the route line below road labels layer on the map
        // the value of this option will depend on the style that you are using
        // and under which layer the route line should be placed on the map layers stack
        val mapboxRouteLineOptions = MapboxRouteLineOptions.Builder(activity)
            .withRouteLineBelowLayerId("road-label")
            .build()
        routeLineApi = MapboxRouteLineApi(mapboxRouteLineOptions)
        routeLineView = MapboxRouteLineView(mapboxRouteLineOptions)

        // initialize maneuver arrow view to draw arrows on the map
        val routeArrowOptions = RouteArrowOptions.Builder(activity).build()
        routeArrowView = MapboxRouteArrowView(routeArrowOptions)

        // load map style
        mapboxMap.loadStyleUri(
            Style.MAPBOX_STREETS
        ) {
            // add long click listener that search for a route to the clicked destination
            binding.mapView.gestures.addOnMapLongClickListener { point ->
                ////findRoute(point)
                true
            }
        }

        // initialize view interactions
        binding.stop.bringToFront()
        binding.stop.isFocusable = true
        binding.stop.setOnClickListener {
            ////clearRouteAndStopNavigation()
            finishNavigation()
        }

        binding.recenter.setOnClickListener {
            navigationCamera.requestNavigationCameraToFollowing()
            binding.routeOverview.showTextAndExtend(BUTTON_ANIMATION_DURATION)
        }
        binding.routeOverview.setOnClickListener {
            navigationCamera.requestNavigationCameraToOverview()
            binding.recenter.showTextAndExtend(BUTTON_ANIMATION_DURATION)
        }
        binding.soundButton.setOnClickListener {
            // mute/unmute voice instructions
            isVoiceInstructionsMuted = !isVoiceInstructionsMuted
        }

        // set initial sounds button state
        binding.soundButton.unmute()
    }

    override fun onMethodCall(methodCall: MethodCall, result: MethodChannel.Result) {
        when (methodCall.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }
            "enableOfflineRouting" -> {
                //downloadRegionForOfflineRouting(call, result)
            }
            "buildRoute" -> {
                buildRoute(methodCall, result)
            }
            "clearRoute" -> {
                clearRoute(methodCall, result)
            }
            "startNavigation" -> {
                startNavigation(methodCall, result)
            }
            "updateNavigation" -> {
                updateNavigation(methodCall, result)
            }
            "finishNavigation" -> {
                finishNavigation(methodCall, result)
            }
            "reCenter" -> {
                navigationCamera.requestNavigationCameraToFollowing()
            }
            "getDistanceRemaining" -> {
                result.success(distanceRemaining)
            }
            "getDurationRemaining" -> {
                result.success(durationRemaining)
            }
            else -> result.notImplemented()
        }
    }

    private fun buildRoute(methodCall: MethodCall, result: MethodChannel.Result) {

        isNavigationCanceled = false
        isNavigationInProgress = false

        val arguments = methodCall.arguments as? Map<*, *>
        if(arguments != null)
            setOptions(arguments)
        val mapReady = true
        if (mapReady) {
            wayPoints.clear()
            val points = arguments?.get("wayPoints") as HashMap<*, *>
            for (item in points)
            {
                val point = item.value as HashMap<*, *>
                val latitude = point["Latitude"] as Double
                val longitude = point["Longitude"] as Double
                wayPoints.add(Point.fromLngLat(longitude, latitude))
            }
            getRoute(context)
            result.success(true)
        } else {
            result.success(false)
        }
    }

    private fun getRoute(context: Context) {

        val originLocation = navigationLocationProvider.lastLocation
//        val originPoint = originLocation?.let {
//            Point.fromLngLat(it.longitude, it.latitude)
//        } ?: return

        if (!PluginUtilities.isNetworkAvailable(context)) {
            PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_FAILED, "No Internet Connection")
            return
        }

        PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILDING)
        Log.d("NAVIGATIONMODE", navigationMode)
        mapboxNavigation.requestRoutes(
            RouteOptions.builder()
                .applyDefaultNavigationOptions(navigationMode)
                .applyLanguageAndVoiceUnitOptions(context)
                .coordinatesList(wayPoints)
                .language(navigationLanguage)
                .alternatives(alternatives)
                .continueStraight(!allowsUTurnAtWayPoints)
                .voiceUnits(navigationVoiceUnits)
                .baseUrl("https://api.mapbox.com")

                /*.bearingsList(
                    listOf(
                        Bearing.builder()
                            //.angle(originLocation.bearing.toDouble())
                            .angle(-60.0)
                            .degrees(45.0)
                            .build(),
                        null
                    )
                )*/
                .build()
            , object : NavigationRouterCallback {

                override fun onRoutesReady(routes: List<NavigationRoute>,
                                           routerOrigin: RouterOrigin) {

                    if (routes.isEmpty()){
                        PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_NO_ROUTES_FOUND)
                        return
                    }

                    currentRoute = routes[0]
                    PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILT)
                    // Draw the route on the map
                    mapboxNavigation.setNavigationRoutes(routes)
                    // move the camera to overview when new route is available
                    navigationCamera.requestNavigationCameraToFollowing()

                    isBuildingRoute = false

                    //Start Navigation again from new Point, if it was already in Progress
                    if (isNavigationInProgress) {
                        startNavigation()
                    }

                }
                override fun onFailure(reasons: List<RouterFailure>,
                                       routeOptions: RouteOptions
                ) {
                    var message = "an error occurred while building the route. Errors: "
                    for (reason in reasons){
                        message += reason.message
                    }
                    PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_FAILED, message)
                    isBuildingRoute = false
                }
                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
                    PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_CANCELLED)
                }
            })
    }

    private fun moveCameraToOriginOfRoute() {
        currentRoute?.let {
            val originCoordinate = it.routeOptions?.coordinatesList()?.get(0)
            originCoordinate?.let {
                val location = Point.fromLngLat(originCoordinate.latitude(), originCoordinate.longitude())
                //moveCameraTo(location)
                //addCustomMarker(location)
            }
        }
    }

    private fun clearRoute(methodCall: MethodCall, result: MethodChannel.Result) {
        mapboxNavigation.setNavigationRoutes(listOf())
        // stop simulation
        mapboxReplayer.stop()

        // hide UI elements
        binding.soundButton.visibility = View.INVISIBLE
        binding.maneuverView.visibility = View.INVISIBLE
        binding.routeOverview.visibility = View.INVISIBLE
        binding.tripProgressCard.visibility = View.INVISIBLE

        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
    }

    private fun startNavigation(methodCall: MethodCall, result: MethodChannel.Result) {

        val arguments = methodCall.arguments as? Map<*, *>
        if(arguments != null)
            setOptions(arguments)

        startNavigation()

        if (currentRoute != null) {
            result.success(true)
        } else {
            result.success(false)
        }
    }

    private fun updateNavigation(methodCall: MethodCall, result: MethodChannel.Result) {

        val arguments = methodCall.arguments as? Map<*, *>

        val points = arguments?.get("wayPoints") as HashMap<Int, Any>
        var stop : Point? = null;
        for (item in points)
        {
            val point = item.value as HashMap<*, *>
            val latitude = point["Latitude"] as Double
            val longitude = point["Longitude"] as Double
            stop = Point.fromLngLat(longitude, latitude)
        }

        if (stop != null) {
            if (!addedWaypoints.isEmpty) {
                addedWaypoints.removeLast()
            }
            addWaypoint(stop, null)
        }

        if (currentRoute != null) {
            result.success(true)
        } else {
            result.success(false)
        }
    }

    //MultiWaypoint Navigation
    private fun addWaypoint(destination: Point, name: String?) {
        val originLocation = navigationLocationProvider.lastLocation
        val originPoint = originLocation?.let {
            Point.fromLngLat(it.longitude, it.latitude)
        } ?: return

        // we always start a route from the current location
        if (addedWaypoints.isEmpty) {
            addedWaypoints.addRegular(originPoint)
        }

        if (name != null) {
            // When you add named waypoints, the string you use here inside "" would be shown in `Maneuver` and played in `Voice` instructions.
            // In this example waypoint names will be visible in the logcat.
            addedWaypoints.addNamed(destination, name)
        } else {
            // When you add silent waypoints, make sure it is followed by a regular or named waypoint, otherwise silent waypoint is treated as a regular waypoint
            addedWaypoints.addSilent(destination)
        }

        // execute a route request
        // it's recommended to use the
        // applyDefaultNavigationOptions and applyLanguageAndVoiceUnitOptions
        // that make sure the route request is optimized
        // to allow for support of all of the Navigation SDK features
        mapboxNavigation.requestRoutes(
            RouteOptions.builder()
                .applyDefaultNavigationOptions(navigationMode)
                .applyLanguageAndVoiceUnitOptions(context)
                .coordinatesList(addedWaypoints.coordinatesList())
                .waypointIndicesList(addedWaypoints.waypointsIndices())
                .waypointNamesList(addedWaypoints.waypointsNames())
                .build(),
            object : NavigationRouterCallback {
                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
                    // no impl
                }

                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                    // no impl
                }

                override fun onRoutesReady(
                    routes: List<NavigationRoute>,
                    routerOrigin: RouterOrigin
                ) {
                    setRoute(routes)
                }
            }
        )
    }

    private fun setRoute(routes: List<NavigationRoute>) {
        // set routes, where the first route in the list is the primary route that
        // will be used for active guidance
        mapboxNavigation.setNavigationRoutes(routes)
    }

    private fun finishNavigation(methodCall: MethodCall, result: MethodChannel.Result) {

        finishNavigation()

        if (currentRoute != null) {
            result.success(true)
        } else {
            result.success(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNavigation() {
        isNavigationCanceled = false

        if (currentRoute != null) {
            if (simulateRoute) {
                mapboxReplayer.play()
            }

            mapboxNavigation.startTripSession()
            // show UI elements
            binding.soundButton.visibility = View.VISIBLE
            binding.routeOverview.visibility = View.VISIBLE
            binding.tripProgressCard.visibility = View.VISIBLE

            navigationCamera.requestNavigationCameraToFollowing()
            isNavigationInProgress = true
            PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_RUNNING)
        }
    }

    private fun finishNavigation(isOffRouted: Boolean = false) {

        // clear
        mapboxNavigation.setNavigationRoutes(listOf())

        zoom = 15.0
        bearing = 0.0
        tilt = 0.0
        isNavigationCanceled = true

        if (!isOffRouted) {
            isNavigationInProgress = false
            moveCameraToOriginOfRoute()
        }

        if(simulateRoute)
            mapboxReplayer.stop()

        mapboxNavigation.stopTripSession()

        // hide UI elements
        binding.soundButton.visibility = View.INVISIBLE
        binding.maneuverView.visibility = View.INVISIBLE
        binding.routeOverview.visibility = View.INVISIBLE
        binding.tripProgressCard.visibility = View.INVISIBLE
        addedWaypoints.clear()
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
    }

    private fun updateCamera(location: Location) {
        val mapAnimationOptions = MapAnimationOptions.Builder().duration(1500L).build()
        binding.mapView.camera.easeTo(
            CameraOptions.Builder()
                // Centers the camera to the lng/lat specified.
                .center(Point.fromLngLat(location.longitude, location.latitude))
                // specifies the zoom value. Increase or decrease to zoom in or zoom out
                .zoom(12.0)
                // specify frame of reference from the center.
                .padding(EdgeInsets(500.0, 0.0, 0.0, 0.0))
                .build(),
            mapAnimationOptions
        )
    }

    private fun setOptions(arguments: Map<*, *>)
    {
        val navMode = arguments["mode"] as? String
        if(navMode != null)
        {
            if(navMode == "walking")
                navigationMode = DirectionsCriteria.PROFILE_WALKING;
            else if(navMode == "cycling")
                navigationMode = DirectionsCriteria.PROFILE_CYCLING;
            else if(navMode == "driving")
                navigationMode = DirectionsCriteria.PROFILE_DRIVING;
        }

        val simulated = arguments["simulateRoute"] as? Boolean
        if (simulated != null) {
            simulateRoute = simulated
        }

        val language = arguments["language"] as? String
        if(language != null)
            navigationLanguage = language

        val units = arguments["units"] as? String

        if(units != null)
        {
            if(units == "imperial")
                navigationVoiceUnits = DirectionsCriteria.IMPERIAL
            else if(units == "metric")
                navigationVoiceUnits = DirectionsCriteria.METRIC
        }

        mapStyleUrlDay = arguments?.get("mapStyleUrlDay") as? String
        mapStyleUrlNight = arguments?.get("mapStyleUrlNight") as? String

        initialLatitude = arguments["initialLatitude"] as? Double
        initialLongitude = arguments["initialLongitude"] as? Double

        val zm = arguments["zoom"] as? Double
        if(zm != null)
            zoom = zm

        val br = arguments["bearing"] as? Double
        if(br != null)
            bearing = br

        val tt = arguments["tilt"] as? Double
        if(tt != null)
            tilt = tt

        val optim = arguments["isOptimized"] as? Boolean
        if(optim != null)
            isOptimized = optim

        val anim = arguments["animateBuildRoute"] as? Boolean
        if(anim != null)
            animateBuildRoute = anim

        val altRoute = arguments["alternatives"] as? Boolean
        if(altRoute != null)
            alternatives = altRoute

        val voiceEnabled = arguments["voiceInstructionsEnabled"] as? Boolean
        if(voiceEnabled != null)
            voiceInstructionsEnabled = voiceEnabled

        val bannerEnabled = arguments["bannerInstructionsEnabled"] as? Boolean
        if(bannerEnabled != null)
            bannerInstructionsEnabled = bannerEnabled

        val longPress = arguments["longPressDestinationEnabled"] as? Boolean
        if(longPress != null)
            longPressDestinationEnabled = longPress
    }

    open fun registerObservers() {
        // register event listeners
        val mapboxNav = mapboxNavigation
        mapboxNav.registerRoutesObserver(routesObserver)
        mapboxNav.registerRouteProgressObserver(routeProgressObserver)
        mapboxNav.registerLocationObserver(locationObserver)
        mapboxNav.registerVoiceInstructionsObserver(voiceInstructionsObserver)
        mapboxNav.registerRouteProgressObserver(replayProgressObserver)
    }

    open fun unregisterObservers() {
        // unregister event listeners to prevent leaks or unnecessary resource consumption
        val mapboxNav = mapboxNavigation
        mapboxNav.unregisterRoutesObserver(routesObserver)
        mapboxNav.unregisterRouteProgressObserver(routeProgressObserver)
        mapboxNav.unregisterLocationObserver(locationObserver)
        mapboxNav.unregisterVoiceInstructionsObserver(voiceInstructionsObserver)
        mapboxNav.unregisterRouteProgressObserver(replayProgressObserver)
    }

    fun onDestroy() {
        Log.d("UTILITY", "OnDestroyLog")
        mapboxReplayer.finish()
        maneuverApi.cancel()
        routeLineApi.cancel()
        routeLineView.cancel()
        speechApi.cancel()
        voiceInstructionsPlayer.shutdown()

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    //Flutter stream listener delegate methods
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        FlutterMapboxNavigationPlugin.eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        FlutterMapboxNavigationPlugin.eventSink = null
    }
    
    val context: Context = ctx
    val activity: Activity = act
    val token: String = accessToken
    open var methodChannel: MethodChannel? = null
    open var eventChannel: EventChannel? = null

    //Config
    var initialLatitude: Double? = null
    var initialLongitude: Double? = null

    val wayPoints: MutableList<Point> = mutableListOf()
    var navigationMode =  DirectionsCriteria.PROFILE_DRIVING_TRAFFIC
    var simulateRoute = false
    var mapStyleUrlDay: String? = null
    var mapStyleUrlNight: String? = null
    var navigationLanguage = "en"
    var navigationVoiceUnits = DirectionsCriteria.IMPERIAL
    var zoom = 15.0
    var bearing = 0.0
    var tilt = 0.0
    var distanceRemaining: Float? = null
    var durationRemaining: Double? = null

    var alternatives = true

    var allowsUTurnAtWayPoints = false
    var enableRefresh = false
    var voiceInstructionsEnabled = true
    var bannerInstructionsEnabled = true
    var longPressDestinationEnabled = true
    var animateBuildRoute = true
    var isOptimized = false

    var originPoint: Point? = null
    var destinationPoint: Point? = null

    private var currentRoute: NavigationRoute? = null
    private var isDisposed = false
    private var isRefreshing = false
    private var isBuildingRoute = false
    private var isNavigationInProgress = false
    private var isNavigationCanceled = false

    val BUTTON_ANIMATION_DURATION = 1500L

    /**
     * Debug tool used to play, pause and seek route progress events that can be used to produce mocked location updates along the route.
     */
    private val mapboxReplayer = MapboxReplayer()

    /**
     * Debug tool that mocks location updates with an input from the [mapboxReplayer].
     */
    private val replayLocationEngine = ReplayLocationEngine(mapboxReplayer)

    /**
     * Debug observer that makes sure the replayer has always an up-to-date information to generate mock updates.
     */
    private val replayProgressObserver = ReplayProgressObserver(mapboxReplayer)

    /**
     * Bindings to the example layout.
     */
    open val binding: NavigationActivityBinding = bind

    /**
     * Mapbox Maps entry point obtained from the [MapView].
     * You need to get a new reference to this object whenever the [MapView] is recreated.
     */
    private lateinit var mapboxMap: MapboxMap

    private val mapboxNavigation: MapboxNavigation by requireMapboxNavigation(
        onResumedObserver = object : MapboxNavigationObserver {
            @SuppressLint("MissingPermission")
            override fun onAttached(mapboxNavigation: MapboxNavigation) {
                mapboxNavigation.registerRoutesObserver(routesObserver)
                mapboxNavigation.registerLocationObserver(locationObserver)
                mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
                mapboxNavigation.registerRouteProgressObserver(replayProgressObserver)
                mapboxNavigation.registerVoiceInstructionsObserver(voiceInstructionsObserver)
                mapboxNavigation.setArrivalController(null)
                // start the trip session to being receiving location updates in free drive
                // and later when a route is set also receiving route progress updates
                mapboxNavigation.startTripSession()
                //findFirstRoute(points[0], points[1])
            }

            override fun onDetached(mapboxNavigation: MapboxNavigation) {
                mapboxNavigation.unregisterRoutesObserver(routesObserver)
                mapboxNavigation.unregisterLocationObserver(locationObserver)
                mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
                mapboxNavigation.unregisterRouteProgressObserver(replayProgressObserver)
                mapboxNavigation.unregisterVoiceInstructionsObserver(voiceInstructionsObserver)

                Log.d("UTILITY", "OnDetached")
            }
        },
        //onInitialize = this::initNavigation
    )

    /**
     * Used to execute camera transitions based on the data generated by the [viewportDataSource].
     * This includes transitions from route overview to route following and continuously updating the camera as the location changes.
     */
    private lateinit var navigationCamera: NavigationCamera

    /**
     * Produces the camera frames based on the location and routing data for the [navigationCamera] to execute.
     */
    private lateinit var viewportDataSource: MapboxNavigationViewportDataSource

    /*
     * Below are generated camera padding values to ensure that the route fits well on screen while
     * other elements are overlaid on top of the map (including instruction view, buttons, etc.)
     */
    private val pixelDensity = Resources.getSystem().displayMetrics.density
    private val overviewPadding: EdgeInsets by lazy {
        EdgeInsets(
            140.0 * pixelDensity,
            40.0 * pixelDensity,
            120.0 * pixelDensity,
            40.0 * pixelDensity
        )
    }
    private val landscapeOverviewPadding: EdgeInsets by lazy {
        EdgeInsets(
            30.0 * pixelDensity,
            380.0 * pixelDensity,
            110.0 * pixelDensity,
            20.0 * pixelDensity
        )
    }
    private val followingPadding: EdgeInsets by lazy {
        EdgeInsets(
            180.0 * pixelDensity,
            40.0 * pixelDensity,
            150.0 * pixelDensity,
            40.0 * pixelDensity
        )
    }
    private val landscapeFollowingPadding: EdgeInsets by lazy {
        EdgeInsets(
            30.0 * pixelDensity,
            380.0 * pixelDensity,
            110.0 * pixelDensity,
            40.0 * pixelDensity
        )
    }

    /**
     * Helper class that keeps added waypoints and transforms them to the [RouteOptions] params.
     */
    private val addedWaypoints = WaypointsSet()

    /**
     * Generates updates for the [MapboxManeuverView] to display the upcoming maneuver instructions
     * and remaining distance to the maneuver point.
     */
    private lateinit var maneuverApi: MapboxManeuverApi

    /**
     * Generates updates for the [MapboxTripProgressView] that include remaining time and distance to the destination.
     */
    private lateinit var tripProgressApi: MapboxTripProgressApi

    /**
     * Generates updates for the [routeLineView] with the geometries and properties of the routes that should be drawn on the map.
     */
    private lateinit var routeLineApi: MapboxRouteLineApi

    /**
     * Draws route lines on the map based on the data from the [routeLineApi]
     */
    private lateinit var routeLineView: MapboxRouteLineView

    /**
     * Generates updates for the [routeArrowView] with the geometries and properties of maneuver arrows that should be drawn on the map.
     */
    private val routeArrowApi: MapboxRouteArrowApi = MapboxRouteArrowApi()

    /**
     * Draws maneuver arrows on the map based on the data [routeArrowApi].
     */
    private lateinit var routeArrowView: MapboxRouteArrowView

    /**
     * Stores and updates the state of whether the voice instructions should be played as they come or muted.
     */
    private var isVoiceInstructionsMuted = false
        set(value) {
            field = value
            if (value) {
                binding.soundButton.muteAndExtend(BUTTON_ANIMATION_DURATION)
                voiceInstructionsPlayer.volume(SpeechVolume(0f))
            } else {
                binding.soundButton.unmuteAndExtend(BUTTON_ANIMATION_DURATION)
                voiceInstructionsPlayer.volume(SpeechVolume(1f))
            }
        }

    /**
     * Extracts message that should be communicated to the driver about the upcoming maneuver.
     * When possible, downloads a synthesized audio file that can be played back to the driver.
     */
    private lateinit var speechApi: MapboxSpeechApi

    /**
     * Plays the synthesized audio files with upcoming maneuver instructions
     * or uses an on-device Text-To-Speech engine to communicate the message to the driver.
     */
    private lateinit var voiceInstructionsPlayer: MapboxVoiceInstructionsPlayer

    /**
     * Observes when a new voice instruction should be played.
     */
    private val voiceInstructionsObserver = VoiceInstructionsObserver { voiceInstructions ->
        speechApi.generate(voiceInstructions, speechCallback)
    }

    /**
     * Based on whether the synthesized audio file is available, the callback plays the file
     * or uses the fall back which is played back using the on-device Text-To-Speech engine.
     */
    private val speechCallback =
        MapboxNavigationConsumer<Expected<SpeechError, SpeechValue>> { expected ->
            expected.fold(
                { error ->
                    // play the instruction via fallback text-to-speech engine
                    voiceInstructionsPlayer.play(
                        error.fallback,
                        voiceInstructionsPlayerCallback
                    )
                },
                { value ->
                    // play the sound file from the external generator
                    voiceInstructionsPlayer.play(
                        value.announcement,
                        voiceInstructionsPlayerCallback
                    )
                }
            )
        }

    /**
     * When a synthesized audio file was downloaded, this callback cleans up the disk after it was played.
     */
    private val voiceInstructionsPlayerCallback =
        MapboxNavigationConsumer<SpeechAnnouncement> { value ->
            // remove already consumed file to free-up space
            speechApi.clean(value)
        }

    /**
     * [NavigationLocationProvider] is a utility class that helps to provide location updates generated by the Navigation SDK
     * to the Maps SDK in order to update the user location indicator on the map.
     */
    private val navigationLocationProvider = NavigationLocationProvider()

    /**
     * Gets notified with location updates.
     *
     * Exposes raw updates coming directly from the location services
     * and the updates enhanced by the Navigation SDK (cleaned up and matched to the road).
     */
    private val locationObserver = object : LocationObserver {
        var firstLocationUpdateReceived = false

        override fun onNewRawLocation(rawLocation: Location) {
            // not handled
            Log.d("onNewRawLocation", rawLocation.toString());
        }

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            val enhancedLocation = locationMatcherResult.enhancedLocation
            // update location puck's position on the map
            navigationLocationProvider.changePosition(
                location = enhancedLocation,
                keyPoints = locationMatcherResult.keyPoints,
            )

            // update camera position to account for new location
            viewportDataSource.onLocationChanged(enhancedLocation)
            viewportDataSource.evaluate()

            // if this is the first location update the activity has received,
            // it's best to immediately move the camera to the current user location
            if (!firstLocationUpdateReceived) {
                firstLocationUpdateReceived = true
                navigationCamera.requestNavigationCameraToFollowing(
                    stateTransitionOptions = NavigationCameraTransitionOptions.Builder()
                        .maxDuration(0) // instant transition
                        .build()
                )
            }
        }
    }

    /**
     * Gets notified with progress along the currently active route.
     */
    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        // update the camera position to account for the progressed fragment of the route
        viewportDataSource.onRouteProgressChanged(routeProgress)
        viewportDataSource.evaluate()

        // draw the upcoming maneuver arrow on the map
        val style = mapboxMap.getStyle()
        if (style != null) {
            val maneuverArrowResult = routeArrowApi.addUpcomingManeuverArrow(routeProgress)
            routeArrowView.renderManeuverUpdate(style, maneuverArrowResult)
        }

        // update top banner with maneuver instructions
        val maneuvers = maneuverApi.getManeuvers(routeProgress)
        maneuvers.fold(
            { error ->
                Toast.makeText(
                    context,
                    error.errorMessage,
                    Toast.LENGTH_SHORT
                ).show()
            },
            {
                binding.maneuverView.visibility = View.VISIBLE
                binding.maneuverView.renderManeuvers(maneuvers)
            }
        )

        // update bottom trip progress summary
        binding.tripProgressView.render(
            tripProgressApi.getTripProgress(routeProgress)
        )

        //update flutter events
        if (!isNavigationCanceled) {
            try {

                distanceRemaining = routeProgress.distanceRemaining
                durationRemaining = routeProgress.durationRemaining

                val progressEvent = MapBoxRouteProgressEvent(routeProgress)
                PluginUtilities.sendEvent(progressEvent)

            } catch (e: java.lang.Exception) {

            }
        }
    }

    /**
     * Gets notified whenever the tracked routes change.
     *
     * A change can mean:
     * - routes get changed with [MapboxNavigation.setRoutes]
     * - routes annotations get refreshed (for example, congestion annotation that indicate the live traffic along the route)
     * - driver got off route and a reroute was executed
     */
    private val routesObserver = RoutesObserver { routeUpdateResult ->

        if (routeUpdateResult.navigationRoutes.isNotEmpty()) {
            // generate route geometries asynchronously and render them
            val routeLines = routeUpdateResult.navigationRoutes

            routeLineApi.setNavigationRoutes(
                routeLines
            ) { value ->
                mapboxMap.getStyle()?.apply {
                    routeLineView.renderRouteDrawData(this, value)
                }
            }

            // update the camera position to account for the new route
            viewportDataSource.onRouteChanged(routeUpdateResult.navigationRoutes.first())
            viewportDataSource.evaluate()

            //send a reroute event
            PluginUtilities.sendEvent(MapBoxEvents.REROUTE_ALONG, routeUpdateResult.reason)

        } else {
            // remove the route line and route arrow from the map
            val style = mapboxMap.getStyle()
            if (style != null) {
                routeLineApi.clearRouteLine { value ->
                    routeLineView.renderClearRouteLineValue(
                        style,
                        value
                    )
                }
                routeArrowView.render(style, routeArrowApi.clearArrows())
            }

            // remove the route reference from camera position evaluations
            viewportDataSource.clearRouteData()
            viewportDataSource.evaluate()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        //lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onActivityStarted(activity: Activity) {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onActivityResumed(activity: Activity) {
        TODO("Not yet implemented")
    }

    override fun onActivityPaused(activity: Activity) {
        TODO("Not yet implemented")
    }

    override fun onActivityStopped(activity: Activity) {
        TODO("Not yet implemented")
        Log.d("UTILITY", "onActivityStopped")
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        TODO("Not yet implemented")
    }

    override fun onActivityDestroyed(activity: Activity) {
        TODO("Not yet implemented")
        Log.d("UTILITY", "onActivityDestroyed")
    }

    override fun getLifecycle(): Lifecycle {
        return lifecycleRegistry
    }
}
