rootProject.name = "idea2strategy-trading-engine"

include(
    "apps:market-gateway",
    "apps:trading-worker",
    "modules:trading-domain",
    "modules:trading-application",
    "modules:strategy-runtime",
    "modules:market-data-adapter",
    "modules:trading-persistence",
    "modules:trading-messaging",
    "modules:trading-common",
)
