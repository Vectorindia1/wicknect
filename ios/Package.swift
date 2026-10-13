// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "HostelCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "HostelCore", targets: ["HostelCore"])],
    dependencies: [.package(url: "https://github.com/scinfu/SwiftSoup.git", from: "2.7.0")],
    targets: [
        .target(name: "HostelCore", dependencies: [.product(name: "SwiftSoup", package: "SwiftSoup")]),
        .testTarget(name: "HostelCoreTests", dependencies: ["HostelCore"]),
    ],
    swiftLanguageVersions: [.v5]
)
