// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "MyBooks",
    platforms: [.macOS(.v14)],
    targets: [
        .binaryTarget(name: "Shared", path: "Shared.xcframework"),
        .executableTarget(
            name: "MyBooks",
            dependencies: ["Shared"],
            path: "Sources/MyBooks"
        ),
    ]
)
