rootProject.name = "BingoRush"

// Game rules and shared files, built for Java 8 so both jars can carry them.
include("bingorush-core")
// Paper 26.x and newer.
include("bingorush-modern")
// Spigot and Paper 1.8.8 to 1.21.x, on Java 8.
include("bingorush-legacy")
