// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "MyBooks",
    platforms: [.macOS(.v14)],
    targets: [
        // Universal Kotlin/Native framework produced by the macOS CI workflow.
        .binaryTarget(name: "Shared", path: "Shared.framework"),
        .executableTarget(
            name: "MyBooks",
            dependencies: ["Shared"],
            path: "Sources/MyBooks"
        ),
    ]
)
