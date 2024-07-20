import Cocoa
import FlutterMacOS

public class CarplayPlugin: NSObject, FlutterPlugin {
    
    private let flutterTextureRegistry: FlutterTextureRegistry
    private var h264Texture: H264Texture?
    private var textureId: Int64?
    
    init(with registry: FlutterTextureRegistry) {
        flutterTextureRegistry = registry
    }
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "carplay", binaryMessenger: registrar.messenger)
        let instance = CarplayPlugin(with: registrar.textures)
        
        registrar.addMethodCallDelegate(instance, channel: channel)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "createTexture":
                        
            h264Texture = H264Texture(packetProcessedCallback: { [self] in
                self.flutterTextureRegistry.textureFrameAvailable(textureId!)
            });
            
            textureId = flutterTextureRegistry.register(h264Texture!)
//            
//            let filePath = Bundle.main.path(forResource: "video", ofType: "h264")
//            let url = URL(fileURLWithPath: filePath!)
//            
//            h264Texture?.decodeFile(url)
            
            result(textureId)
        
            break;
            
        case "removeTexture":

            if (textureId != nil) {
                flutterTextureRegistry.unregisterTexture(textureId!)
                textureId = nil
                h264Texture = nil;
            }
            
            result(nil)

            
            break;

        case "processData":

            if (h264Texture != nil && call.arguments is FlutterStandardTypedData) {
                let uintInt8List =  call.arguments as! FlutterStandardTypedData
                
                h264Texture?.processData(data: [UInt8](uintInt8List.data))
            }
            
            result(nil)

            break;

        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    
    
}
