import Flutter
import UIKit
import CallKit
import AVFoundation
import UserNotifications

/// Where one call stands on this device — see ownCallState(_:).
public enum CallkitOwnCallState {
    /// Not in CallKit here (never reported, or already ended).
    case none
    /// Incoming, on the system UI, not answered yet.
    case ringing
    /// Answered on this device, or placed from it.
    case answered
}

@available(iOS 10.0, *)
public class SwiftFlutterCallkitIncomingPlugin: NSObject, FlutterPlugin, CXProviderDelegate {
    
    static let ACTION_DID_UPDATE_DEVICE_PUSH_TOKEN_VOIP = "com.hiennv.flutter_callkit_incoming.DID_UPDATE_DEVICE_PUSH_TOKEN_VOIP"
    
    static let ACTION_CALL_INCOMING = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_INCOMING"
    static let ACTION_CALL_START = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_START"
    static let ACTION_CALL_ACCEPT = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_ACCEPT"
    static let ACTION_CALL_DECLINE = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_DECLINE"
    static let ACTION_CALL_ENDED = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_ENDED"
    static let ACTION_CALL_TIMEOUT = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_TIMEOUT"
    static let ACTION_CALL_CALLBACK = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_CALLBACK"
    static let ACTION_CALL_CUSTOM = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_CUSTOM"
    static let ACTION_CALL_CONNECTED = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_CONNECTED"
    
    static let ACTION_CALL_PROVIDER_DID_RESET = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_PROVIDER_DID_RESET"
    
    static let ACTION_CALL_TOGGLE_HOLD = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_TOGGLE_HOLD"
    static let ACTION_CALL_TOGGLE_MUTE = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_TOGGLE_MUTE"
    static let ACTION_CALL_TOGGLE_DMTF = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_TOGGLE_DMTF"
    static let ACTION_CALL_TOGGLE_GROUP = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_TOGGLE_GROUP"
    static let ACTION_CALL_TOGGLE_AUDIO_SESSION = "com.hiennv.flutter_callkit_incoming.ACTION_CALL_TOGGLE_AUDIO_SESSION"
    
    @objc public private(set) static var sharedInstance: SwiftFlutterCallkitIncomingPlugin!
    
    private var streamHandlers: WeakArray<EventCallbackHandler> = WeakArray([])
    
    private var callManager: CallManager
    
    private var sharedProvider: CXProvider? = nil
    
    private var outgoingCall : Call?
    private var answerCall : Call?
    
    private var data: Data?
    private var isFromPushKit: Bool = false
    private var silenceEvents: Bool = false
    private let devicePushTokenVoIP = "DevicePushTokenVoIP"

    
    private func sendEvent(_ event: String, _ body: [String : Any?]?) {
        if silenceEvents {
            print(event, " silenced")
            return
        } else {
            streamHandlers.reap().forEach { handler in
                handler?.send(event, body ?? [:])
            }
        }
        
    }
    
    @objc public func sendEventCustom(_ event: String, body: NSDictionary?) {
        streamHandlers.reap().forEach { handler in
            handler?.send(event, body ?? [:])
        }
    }
    
    public static func sharePluginWithRegister(with registrar: FlutterPluginRegistrar) {
        if(sharedInstance == nil){
            sharedInstance = SwiftFlutterCallkitIncomingPlugin(messenger: registrar.messenger())
        }
        sharedInstance.shareHandlers(with: registrar)
    }
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        sharePluginWithRegister(with: registrar)
    }
    
    private static func createMethodChannel(messenger: FlutterBinaryMessenger) -> FlutterMethodChannel {
        return FlutterMethodChannel(name: "flutter_callkit_incoming", binaryMessenger: messenger)
    }
    
    private static func createEventChannel(messenger: FlutterBinaryMessenger) -> FlutterEventChannel {
        return FlutterEventChannel(name: "flutter_callkit_incoming_events", binaryMessenger: messenger)
    }
    
    public init(messenger: FlutterBinaryMessenger) {
        callManager = CallManager()
    }
    
    private func shareHandlers(with registrar: FlutterPluginRegistrar) {
        registrar.addMethodCallDelegate(self, channel: Self.createMethodChannel(messenger: registrar.messenger()))
        let eventsHandler = EventCallbackHandler()
        self.streamHandlers.append(eventsHandler)
        Self.createEventChannel(messenger: registrar.messenger()).setStreamHandler(eventsHandler)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "showCallkitIncoming":
            guard let args = call.arguments else {
                result(true)
                return
            }
            if let getArgs = args as? [String: Any] {
                self.data = Data(args: getArgs)
                showCallkitIncoming(self.data!, fromPushKit: false)
            }
            result(true)
            break
        case "showMissCallNotification":
            guard let args = call.arguments else {
                result(true)
                return
            }
            if let getArgs = args as? [String: Any] {
                self.data = Data(args: getArgs)
                self.showMissedCallNotification(data!)
            }
            result(true)
            break
        case "startCall":
            guard let args = call.arguments else {
                result(true)
                return
            }
            if let getArgs = args as? [String: Any] {
                self.data = Data(args: getArgs)
                self.startCall(self.data!, fromPushKit: false)
            }
            result(true)
            break
        case "endCall":
            // The id the app names is the call that ends. This used to end the
            // call of the LAST VoIP push whenever one had arrived, whatever id
            // was passed — see endCall(_:).
            if let getArgs = call.arguments as? [String: Any] {
                self.endCall(Data(args: getArgs))
            }
            result(true)
            break
        case "muteCall":
            guard let args = call.arguments as? [String: Any] ,
                  let callId = args["id"] as? String,
                  let isMuted = args["isMuted"] as? Bool else {
                result(true)
                return
            }
            
            self.muteCall(callId, isMuted: isMuted)
            result(true)
            break
        case "isMuted":
            guard let args = call.arguments as? [String: Any] ,
                  let callId = args["id"] as? String else{
                result(false)
                return
            }
            guard let callUUID = UUID(uuidString: callId),
                  let call = self.callManager.callWithUUID(uuid: callUUID) else {
                result(false)
                return
            }
            result(call.isMuted)
            break
        case "holdCall":
            guard let args = call.arguments as? [String: Any] ,
                  let callId = args["id"] as? String,
                  let onHold = args["isOnHold"] as? Bool else {
                result(true)
                return
            }
            self.holdCall(callId, onHold: onHold)
            result(true)
            break
        case "callConnected":
            // Same rule as endCall: the named call, not the last pushed one.
            if let getArgs = call.arguments as? [String: Any] {
                self.connectedCall(Data(args: getArgs))
            }
            result(true)
            break
        case "activeCalls":
            result(self.callManager.activeCalls())
            break;
        case "endAllCalls":
            self.callManager.endCallAlls()
            result(true)
            break
        case "getDevicePushTokenVoIP":
            result(self.getDevicePushTokenVoIP())
            break;
        case "silenceEvents":
            guard let silence = call.arguments as? Bool else {
                result(true)
                return
            }
            
            self.silenceEvents = silence
            result(true)
            break;
        case "requestNotificationPermission":
            guard let args = call.arguments else {
                result(true)
                return
            }
            if let getArgs = args as? [String: Any] {
                self.requestNotificationPermission(getArgs)
            }
            result(true)
            break
         case "requestFullIntentPermission": 
            result(true)
            break
         case "canUseFullScreenIntent": 
            result(true)
            break
        case "hideCallkitIncoming":
            // CallKit has no "stop ringing but keep the call": a ringing call
            // leaves the screen only by ending. It ends here as a call that was
            // NOT taken on this device's system UI — reported, not requested, so
            // no DECLINE comes back (see reportCallEnded). An answered call is
            // not "ringing" and is left alone: hiding must never hang up.
            if let getArgs = call.arguments as? [String: Any],
               let id = getArgs["id"] as? String,
               self.ownCallState(id) == .ringing {
                self.reportCallEnded(id, reason: .remoteEnded)
            }
            result(true)
            break
        case "endNativeSubsystemOnly":
            result(true)
            break
        case "setAudioRoute":
            result(true)
            break
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    @objc public func setDevicePushTokenVoIP(_ deviceToken: String) {
        UserDefaults.standard.set(deviceToken, forKey: devicePushTokenVoIP)
        self.sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_DID_UPDATE_DEVICE_PUSH_TOKEN_VOIP, ["deviceTokenVoIP":deviceToken])
    }
    
    @objc public func getDevicePushTokenVoIP() -> String {
        return UserDefaults.standard.string(forKey: devicePushTokenVoIP) ?? ""
    }
    
    @objc public func getAcceptedCall() -> Data? {
        NSLog("Call data ids \(String(describing: data?.uuid)) \(String(describing: answerCall?.uuid.uuidString))")
        if data?.uuid.lowercased() == answerCall?.uuid.uuidString.lowercased() {
            return data
        }
        return nil
    }
    
    @objc public func showCallkitIncoming(_ data: Data, fromPushKit: Bool, onError: ((Error?) -> Void)? = nil) {
        self.isFromPushKit = fromPushKit
        if(fromPushKit){
            self.data = data
        }
        
        if(data.isShowMissedCallNotification){
            CallkitNotificationManager.shared.addNotificationCategory(data.missedNotificationCallbackText)
        }
        
        var handle: CXHandle?
        handle = CXHandle(type: self.getHandleType(data.handleType), value: data.getEncryptHandle())
        
        let callUpdate = CXCallUpdate()

        callUpdate.remoteHandle = handle
        callUpdate.supportsDTMF = data.supportsDTMF
        callUpdate.supportsHolding = data.supportsHolding
        callUpdate.supportsGrouping = data.supportsGrouping
        callUpdate.supportsUngrouping = data.supportsUngrouping
        callUpdate.hasVideo = data.type > 0 ? true : false
        callUpdate.localizedCallerName = data.nameCaller
        
        initCallkitProvider(data)
        
        // Guard against malformed UUID — see CallManager.swift:startCall for rationale.
        guard let uuid = UUID(uuidString: data.uuid) else {
            NSLog("[CallkitIncoming] showIncomingCall(no PushKit): invalid UUID '\(data.uuid)' — ignored")
            return
        }
        
        self.configureAudioSession()
        self.sharedProvider?.reportNewIncomingCall(with: uuid, update: callUpdate) { error in
            if(error == nil) {
                self.configureAudioSession()
                let call = Call(uuid: uuid, data: data)
                call.handle = data.handle
                self.callManager.addCall(call)
                self.sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_INCOMING, data.toJSON())
                self.endCallNotExist(data)
            } else {
                onError?(error)
            }
        }
    }
    
    @objc public func showCallkitIncoming(_ data: Data, fromPushKit: Bool) {
        self.showCallkitIncoming(data, fromPushKit: fromPushKit, onError: nil)
    }
    
    @objc public func showCallkitIncoming(_ data: Data, fromPushKit: Bool, completion: @escaping () -> Void) {
        self.isFromPushKit = fromPushKit
        if(fromPushKit){
            self.data = data
        }
        
        if(data.isShowMissedCallNotification){
            CallkitNotificationManager.shared.addNotificationCategory(data.missedNotificationCallbackText)
        }
        
        var handle: CXHandle?
        handle = CXHandle(type: self.getHandleType(data.handleType), value: data.getEncryptHandle())
        
        let callUpdate = CXCallUpdate()
        callUpdate.remoteHandle = handle
        callUpdate.supportsDTMF = data.supportsDTMF
        callUpdate.supportsHolding = data.supportsHolding
        callUpdate.supportsGrouping = data.supportsGrouping
        callUpdate.supportsUngrouping = data.supportsUngrouping
        callUpdate.hasVideo = data.type > 0 ? true : false
        callUpdate.localizedCallerName = data.nameCaller
        
        initCallkitProvider(data)
        
        // Guard against malformed UUID — see CallManager.swift:startCall for rationale.
        // PushKit call MUST report within ~5s deadline; on invalid UUID we still call
        // completion() so iOS doesn't penalize the app for the missed deadline.
        guard let uuid = UUID(uuidString: data.uuid) else {
            NSLog("[CallkitIncoming] showCallkitIncoming: invalid UUID '\(data.uuid)' — ignored")
            completion()
            return
        }
        
        self.sharedProvider?.reportNewIncomingCall(with: uuid, update: callUpdate) { error in
            if(error == nil) {
                self.configureAudioSession()
                let call = Call(uuid: uuid, data: data)
                call.handle = data.handle
                self.callManager.addCall(call)
                self.sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_INCOMING, data.toJSON())
                self.endCallNotExist(data)
            }
            completion()
        }
    }
    
    
    @objc public func startCall(_ data: Data, fromPushKit: Bool) {
        self.isFromPushKit = fromPushKit
        if(fromPushKit){
            self.data = data
        }
        initCallkitProvider(data)
        self.callManager.startCall(data)
    }
    
    @objc public func muteCall(_ callId: String, isMuted: Bool) {
        guard let callId = UUID(uuidString: callId),
              let call = self.callManager.callWithUUID(uuid: callId) else {
            return
        }
        if call.isMuted == isMuted {
            self.sendMuteEvent(callId.uuidString, isMuted)
        } else {
            self.callManager.muteCall(call: call, isMuted: isMuted)
        }
    }
    
    @objc public func holdCall(_ callId: String, onHold: Bool) {
        guard let callId = UUID(uuidString: callId),
              let call = self.callManager.callWithUUID(uuid: callId) else {
            return
        }
        if call.isOnHold == onHold {
            self.sendHoldEvent(callId.uuidString, onHold)
        } else {
            self.callManager.holdCall(call: call, onHold: onHold)
        }
    }
    
    /// Ends the call [data] names.
    ///
    /// It used to end the call of the LAST VoIP push instead whenever one had
    /// arrived (`isFromPushKit`), and that flag stays set until something resets
    /// it — a call declined or missed on the CallKit UI does not. So with a
    /// pushed call still on record, an app hanging up a DIFFERENT call (its own
    /// outgoing call, a second call) ended the stale one and left the live call
    /// stuck in the system UI.
    ///
    /// It also sent ACTION_CALL_ENDED itself before requesting the end. The
    /// CXEndCallAction handler reports the outcome (ENDED or DECLINE) once the
    /// end is performed; the early copy was a second event for the same call,
    /// sent even when the request then failed.
    @objc public func endCall(_ data: Data) {
        // Guard against malformed UUID — see CallManager.swift:startCall for rationale.
        guard let uuid = UUID(uuidString: data.uuid) else {
            NSLog("[CallkitIncoming] endCall: invalid UUID '\(data.uuid)' — ignored")
            return
        }
        self.forgetPushedCall(uuid)
        self.callManager.endCall(call: Call(uuid: uuid, data: data))
    }

    /// Ends a call that ended somewhere ELSE — the caller hung up, the server
    /// gave up ringing, another device of the user answered or declined it.
    ///
    /// CallKit has two ways to end a call and they are not interchangeable:
    /// a CXEndCallAction is the USER of this device ending it, and the delegate
    /// answers it with ACTION_CALL_DECLINE (never answered) or ACTION_CALL_ENDED.
    /// An app turns DECLINE into a "declined" request to its server — for a call
    /// that was cancelled, or answered on another device, that is a decline the
    /// user never made. `reportCall(with:endedAt:reason:)` is CallKit's API for
    /// a call ended outside this device: the system UI closes, Recents gets the
    /// right entry, and no action (so no event) is produced.
    ///
    /// The call's bookkeeping is released as the delegate would have released
    /// it; left behind, the ring timeout would later fire for it and post a
    /// missed-call notification for a call that was answered elsewhere.
    @objc public func reportCallEnded(_ id: String, reason: CXCallEndedReason) {
        guard let uuid = UUID(uuidString: id) else {
            NSLog("[CallkitIncoming] reportCallEnded: invalid UUID '\(id)' — ignored")
            return
        }
        self.sharedProvider?.reportCall(with: uuid, endedAt: Date(), reason: reason)
        self.forgetPushedCall(uuid)
        guard let call = self.callManager.callWithUUID(uuid: uuid) else { return }
        call.endCall()
        self.callManager.removeCall(call)
        if self.answerCall === call { self.answerCall = nil }
        if self.outgoingCall === call { self.outgoingCall = nil }
    }

    /// Where a call stands on THIS device, as CallKit knows it.
    ///
    /// A host app needs this to handle a signal about a call correctly: a
    /// "stop ringing" signal means one thing for a call still ringing here and
    /// the opposite for a call this device has answered.
    public func ownCallState(_ id: String) -> CallkitOwnCallState {
        guard let uuid = UUID(uuidString: id),
              let call = self.callManager.callWithUUID(uuid: uuid),
              !call.hasEnded else {
            return .none
        }
        if call.isOutGoing || call.data.isAccepted || call.hasConnected {
            return .answered
        }
        return .ringing
    }

    /// Whether this app has a call in CallKit that has not ended — ringing,
    /// answered or placed. Calls of other apps and cellular calls do not count.
    public var hasOwnLiveCall: Bool {
        return self.callManager.calls.contains { !$0.hasEnded }
    }

    /// The pushed call is done with: the PushKit branches of this class stop
    /// reading its stored data.
    private func forgetPushedCall(_ uuid: UUID) {
        if let stored = self.data, UUID(uuidString: stored.uuid) == uuid {
            self.isFromPushKit = false
        }
    }
    
    /// Reports the call [data] names as connected — for an INCOMING call still
    /// ringing on the system UI this answers it there (the user answered inside
    /// the app). Like endCall(_:), it used to act on the last pushed call.
    @objc public func connectedCall(_ data: Data) {
        // Guard against malformed UUID — see CallManager.swift:startCall for rationale.
        guard let uuid = UUID(uuidString: data.uuid) else {
            NSLog("[CallkitIncoming] connectedCall: invalid UUID '\(data.uuid)' — ignored")
            return
        }
        self.callManager.connectedCall(call: Call(uuid: uuid, data: data))
    }
    
    @objc public func activeCalls() -> [[String: Any]] {
        return self.callManager.activeCalls()
    }
    
    @objc public func endAllCalls() {
        self.isFromPushKit = false
        self.callManager.endCallAlls()
    }
    
    public func saveEndCall(_ uuid: String, _ reason: Int) {
        // Guard against malformed UUID — see CallManager.swift:startCall for rationale.
        // Single guard at top covers all five branches.
        guard let callUuid = UUID(uuidString: uuid) else {
            NSLog("[CallkitIncoming] saveEndCall: invalid UUID '\(uuid)' (reason=\(reason)) — ignored")
            return
        }
        switch reason {
        case 1:
            self.sharedProvider?.reportCall(with: callUuid, endedAt: Date(), reason: CXCallEndedReason.failed)
            break
        case 2, 6:
            self.sharedProvider?.reportCall(with: callUuid, endedAt: Date(), reason: CXCallEndedReason.remoteEnded)
            break
        case 3:
            self.sharedProvider?.reportCall(with: callUuid, endedAt: Date(), reason: CXCallEndedReason.unanswered)
            break
        case 4:
            self.sharedProvider?.reportCall(with: callUuid, endedAt: Date(), reason: CXCallEndedReason.answeredElsewhere)
            break
        case 5:
            self.sharedProvider?.reportCall(with: callUuid, endedAt: Date(), reason: CXCallEndedReason.declinedElsewhere)
            break
        default:
            break
        }
    }
    
    
    func endCallNotExist(_ data: Data) {
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(data.duration)) {
            // Guard against malformed UUID — see CallManager.swift:startCall for rationale.
            guard let uuid = UUID(uuidString: data.uuid) else {
                NSLog("[CallkitIncoming] endCallNotExist: invalid UUID '\(data.uuid)' — ignored")
                return
            }
            let call = self.callManager.callWithUUID(uuid: uuid)
            if (call != nil && self.answerCall == nil && self.outgoingCall == nil) {
                self.callEndTimeout(data)
            }
        }
    }
    
    
    
    func callEndTimeout(_ data: Data) {
        // Guard against malformed UUID — see CallManager.swift:startCall for rationale.
        guard let uuid = UUID(uuidString: data.uuid) else {
            NSLog("[CallkitIncoming] callEndTimeout: invalid UUID '\(data.uuid)' — ignored")
            return
        }
        guard let call = self.callManager.callWithUUID(uuid: uuid) else {
            self.saveEndCall(data.uuid, 3)
            return
        }
        // Reported AND released. Only reporting it (saveEndCall) left the call
        // on record as live forever: every later question about it — "is this
        // call ringing?", "does the app have a live call?" — answered yes for a
        // call the system had already ended.
        self.reportCallEnded(data.uuid, reason: .unanswered)
        self.showMissedCallNotification(data)
        sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_TIMEOUT, data.toJSON())
        if let appDelegate = UIApplication.shared.delegate as? CallkitIncomingAppDelegate {
            appDelegate.onTimeOut(call)
        }
    }
    
    func getHandleType(_ handleType: String?) -> CXHandle.HandleType {
        var typeDefault = CXHandle.HandleType.generic
        switch handleType {
        case "number":
            typeDefault = CXHandle.HandleType.phoneNumber
            break
        case "email":
            typeDefault = CXHandle.HandleType.emailAddress
        default:
            typeDefault = CXHandle.HandleType.generic
        }
        return typeDefault
    }
    
    func initCallkitProvider(_ data: Data) {
        if(self.sharedProvider == nil){
            self.sharedProvider = CXProvider(configuration: createConfiguration(data))
            self.sharedProvider?.setDelegate(self, queue: nil)
        } else {
            self.sharedProvider?.configuration = createConfiguration(data)
        }
        self.callManager.setSharedProvider(self.sharedProvider!)
    }
    
    func createConfiguration(_ data: Data) -> CXProviderConfiguration {
        let configuration = CXProviderConfiguration(localizedName: data.appName)
        configuration.supportsVideo = data.supportsVideo
        configuration.maximumCallGroups = data.maximumCallGroups
        configuration.maximumCallsPerCallGroup = data.maximumCallsPerCallGroup
        
        configuration.supportedHandleTypes = [
            CXHandle.HandleType.generic,
            CXHandle.HandleType.emailAddress,
            CXHandle.HandleType.phoneNumber
        ]
        if #available(iOS 11.0, *) {
            configuration.includesCallsInRecents = data.includesCallsInRecents
        }
        if !data.iconName.isEmpty {
            if let image = UIImage(named: data.iconName) {
                configuration.iconTemplateImageData = image.pngData()
            } else {
                print("Unable to load icon \(data.iconName).");
            }
        }
        if !data.ringtonePath.isEmpty || data.ringtonePath != "system_ringtone_default"  {
            configuration.ringtoneSound = data.ringtonePath
        }
        return configuration
    }
    
    func sendDefaultAudioInterruptionNotificationToStartAudioResource(){
        var userInfo : [AnyHashable : Any] = [:]
        let intrepEndeRaw = AVAudioSession.InterruptionType.ended.rawValue
        userInfo[AVAudioSessionInterruptionTypeKey] = intrepEndeRaw
        userInfo[AVAudioSessionInterruptionOptionKey] = AVAudioSession.InterruptionOptions.shouldResume.rawValue
        NotificationCenter.default.post(name: AVAudioSession.interruptionNotification, object: self, userInfo: userInfo)
    }
    
    func configureAudioSession(){
        if data?.configureAudioSession != false {
            let session = AVAudioSession.sharedInstance()
            do{
                try session.setCategory(AVAudioSession.Category.playAndRecord, options: [
                    .allowBluetoothA2DP,
                    .duckOthers,
                    .allowBluetooth,
                ])
                
                try session.setMode(self.getAudioSessionMode(data?.audioSessionMode))
                try session.setActive(data?.audioSessionActive ?? true)
                try session.setPreferredSampleRate(data?.audioSessionPreferredSampleRate ?? 44100.0)
                try session.setPreferredIOBufferDuration(data?.audioSessionPreferredIOBufferDuration ?? 0.005)
            }catch{
                print(error)
            }
        }
    }
    
    func getAudioSessionMode(_ audioSessionMode: String?) -> AVAudioSession.Mode {
        var mode = AVAudioSession.Mode.default
        switch audioSessionMode {
        case "gameChat":
            mode = AVAudioSession.Mode.gameChat
            break
        case "measurement":
            mode = AVAudioSession.Mode.measurement
            break
        case "moviePlayback":
            mode = AVAudioSession.Mode.moviePlayback
            break
        case "spokenAudio":
            mode = AVAudioSession.Mode.spokenAudio
            break
        case "videoChat":
            mode = AVAudioSession.Mode.videoChat
            break
        case "videoRecording":
            mode = AVAudioSession.Mode.videoRecording
            break
        case "voiceChat":
            mode = AVAudioSession.Mode.voiceChat
            break
        case "voicePrompt":
            if #available(iOS 12.0, *) {
                mode = AVAudioSession.Mode.voicePrompt
            } else {
                // Fallback on earlier versions
            }
            break
        default:
            mode = AVAudioSession.Mode.default
        }
        return mode
    }
    
    public func providerDidReset(_ provider: CXProvider) {
        for call in self.callManager.calls {
            call.endCall()
        }
        self.callManager.removeAllCalls()
        sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_PROVIDER_DID_RESET, [:])
        if let appDelegate = UIApplication.shared.delegate as? CallkitIncomingAppDelegate {
            appDelegate.providerDidReset()
        }
    }
    
    public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        let call = Call(uuid: action.callUUID, data: self.data!, isOutGoing: true)
        call.handle = action.handle.value
        configureAudioSession()
        call.hasStartedConnectDidChange = { [weak self] in
            self?.sharedProvider?.reportOutgoingCall(with: call.uuid, startedConnectingAt: call.connectData)
        }
        call.hasConnectDidChange = { [weak self] in
            self?.sharedProvider?.reportOutgoingCall(with: call.uuid, connectedAt: call.connectedData)
        }
        self.outgoingCall = call;
        self.callManager.addCall(call)
        self.sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_START, self.data?.toJSON())
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        guard let call = self.callManager.callWithUUID(uuid: action.callUUID) else{
            action.fail()
            return
        }
        self.configureAudioSession()
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(1200)) {
            self.configureAudioSession()
        }


        call.hasConnectDidChange = { [weak self] in
            self?.sharedProvider?.reportOutgoingCall(with: call.uuid, connectedAt: call.connectedData)
        }
        call.data.isAccepted = true
        self.answerCall = call
        sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_ACCEPT, call.data.toJSON())
        if let appDelegate = UIApplication.shared.delegate as? CallkitIncomingAppDelegate {
            appDelegate.onAccept(call, action)
        }else {
            action.fulfill()
        }
    }
    
//    private func checkUnlockedAndFulfill(action: CXAnswerCallAction, counter: Int) {
//        if UIApplication.shared.isProtectedDataAvailable {
//            action.fulfill()
//        } else if counter > 180 { // fail if waiting for more then 3 minutes
//            action.fail()
//        } else {
//            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
//                self.checkUnlockedAndFulfill(action: action, counter: counter + 1)
//            }
//        }
//    }
    
    
    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        guard let call = self.callManager.callWithUUID(uuid: action.callUUID) else {
            if(self.answerCall == nil && self.outgoingCall == nil){
                sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_TIMEOUT, self.data?.toJSON())
            } else {
                sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_ENDED, self.data?.toJSON())
            }
            action.fail()
            return
        }
        call.endCall()
        self.callManager.removeCall(call)
        if (self.answerCall == nil && self.outgoingCall == nil) {
            // The DECLINED call's data, not self.data: self.data is whatever the
            // app last sent or the last push carried, and an app declines the
            // call whose id this event names.
            sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_DECLINE, call.data.toJSON())
            if let appDelegate = UIApplication.shared.delegate as? CallkitIncomingAppDelegate {
                appDelegate.onDecline(call, action)
            } else {
                action.fulfill()
            }
        }else {
            // Release BOTH slots, the outgoing one included. They are single
            // slots reused across calls, and the branch above reads them to tell
            // a DECLINE (nothing was ever answered or placed) from an END. Only
            // answerCall was released here, so once an app placed an outgoing
            // call the reference stayed forever and every later incoming call the
            // user rejected from the CallKit UI reported ACTION_CALL_ENDED
            // instead of ACTION_CALL_DECLINE — the decline never reached the app.
            self.answerCall = nil
            self.outgoingCall = nil
            sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_ENDED, call.data.toJSON())
            if let appDelegate = UIApplication.shared.delegate as? CallkitIncomingAppDelegate {
                appDelegate.onEnd(call, action)
            } else {
                action.fulfill()
            }
        }
    }
    
    
    public func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        guard let call = self.callManager.callWithUUID(uuid: action.callUUID) else {
            action.fail()
            return
        }
        call.isOnHold = action.isOnHold
        call.isMuted = action.isOnHold
        self.callManager.setHold(call: call, onHold: action.isOnHold)
        sendHoldEvent(action.callUUID.uuidString, action.isOnHold)
        if !action.isOnHold {
            sendDefaultAudioInterruptionNotificationToStartAudioResource()
        }
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        guard let call = self.callManager.callWithUUID(uuid: action.callUUID) else {
            action.fail()
            return
        }
        call.isMuted = action.isMuted
        sendMuteEvent(action.callUUID.uuidString, action.isMuted)
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, perform action: CXSetGroupCallAction) {
        guard (self.callManager.callWithUUID(uuid: action.callUUID)) != nil else {
            action.fail()
            return
        }
        self.sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_TOGGLE_GROUP, [ "id": action.callUUID.uuidString, "callUUIDToGroupWith" : action.callUUIDToGroupWith?.uuidString])
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        guard (self.callManager.callWithUUID(uuid: action.callUUID)) != nil else {
            action.fail()
            return
        }
        self.sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_TOGGLE_DMTF, [ "id": action.callUUID.uuidString, "digits": action.digits, "type": action.type.rawValue ])
        action.fulfill()
    }
    
    
    public func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
        guard let call = self.callManager.callWithUUID(uuid: action.uuid) else {
            action.fail()
            return
        }
        sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_TIMEOUT, self.data?.toJSON())
        if let appDelegate = UIApplication.shared.delegate as? CallkitIncomingAppDelegate {
            appDelegate.onTimeOut(call)
        }
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {

        if let appDelegate = UIApplication.shared.delegate as? CallkitIncomingAppDelegate {
            appDelegate.didActivateAudioSession(audioSession)
        }

        if(self.answerCall?.hasConnected ?? false){
            sendDefaultAudioInterruptionNotificationToStartAudioResource()
            return
        }
        if(self.outgoingCall?.hasConnected ?? false){
            sendDefaultAudioInterruptionNotificationToStartAudioResource()
            return
        }
        self.outgoingCall?.startCall(withAudioSession: audioSession) {success in
            if success {
                self.callManager.addCall(self.outgoingCall!)
                self.outgoingCall?.startAudio()
            }
        }
        self.answerCall?.ansCall(withAudioSession: audioSession) { success in
            if success{
                self.answerCall?.startAudio()
            }
        }
        sendDefaultAudioInterruptionNotificationToStartAudioResource()
        configureAudioSession()

        self.sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_TOGGLE_AUDIO_SESSION, [ "isActivate": true ])
    }
    
    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        
        if let appDelegate = UIApplication.shared.delegate as? CallkitIncomingAppDelegate {
            appDelegate.didDeactivateAudioSession(audioSession)
        }

        if self.outgoingCall?.isOnHold ?? false || self.answerCall?.isOnHold ?? false{
            print("Call is on hold")
            return
        }
        
        self.sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_TOGGLE_AUDIO_SESSION, [ "isActivate": false ])
    }
    
    private func sendMuteEvent(_ id: String, _ isMuted: Bool) {
        self.sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_TOGGLE_MUTE, [ "id": id, "isMuted": isMuted ])
    }
    
    private func sendHoldEvent(_ id: String, _ isOnHold: Bool) {
        self.sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_TOGGLE_HOLD, [ "id": id, "isOnHold": isOnHold ])
    }
    
    @objc public func sendCallbackEvent(_ data: [String: Any]?) {
        self.sendEvent(SwiftFlutterCallkitIncomingPlugin.ACTION_CALL_CALLBACK, data)
    }
    
    
    private func requestNotificationPermission(_ map: [String: Any]) {
        CallkitNotificationManager.shared.requestNotificationPermission(map)
    }
    
    
    private func showMissedCallNotification(_ data: Data) {
        if(!data.isShowMissedCallNotification){
            return
        }
        
        let content = UNMutableNotificationContent()
        content.title = "\(data.nameCaller)"
        content.body = "\(data.missedNotificationSubtitle)"
        content.sound = UNNotificationSound.default
        content.categoryIdentifier = "MISSED_CALL_CATEGORY"
        content.userInfo = data.toJSON()

        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)

        let request = UNNotificationRequest(
            identifier: data.uuid,
            content: content,
            trigger: trigger
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Error scheduling missed call notification: \(error)")
            } else {
                print("Missed call notification scheduled.")
            }
        }
    }
    
}

class EventCallbackHandler: NSObject, FlutterStreamHandler {
    private var eventSink: FlutterEventSink?
    
    public func send(_ event: String, _ body: Any) {
        let data: [String : Any] = [
            "event": event,
            "body": body
        ]
        eventSink?(data)
    }
    
    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        return nil
    }
    
    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        self.eventSink = nil
        return nil
    }
}

@available(iOS 10.0, *)
@objc(FlutterCallkitIncomingPlugin)
public class FlutterCallkitIncomingPlugin: NSObject, FlutterPlugin {
    @objc public static func register(with registrar: FlutterPluginRegistrar) {
        SwiftFlutterCallkitIncomingPlugin.register(with: registrar)
    }
}
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          