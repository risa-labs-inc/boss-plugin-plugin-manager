MCP API floor regression fixture

mcp-tool-definition-1.0.73.zip contains only McpToolDefinition and its Companion,
extracted unchanged from the published boss-plugin-api-1.0.73.jar release:
https://github.com/risa-labs-inc/boss-plugin-api/releases/tag/v1.0.73
Original JAR SHA-256: 7e01a80d91087b95c968b98f6ffcac9ce56867e41a09b25baef066cc6324109e

Source tag v1.0.73 resolves to 9b4e4a0. The factory was introduced in 41294ed
(1.0.52), an ancestor of that tag. PluginManagerMcpToolsTest loads the actual
compiled Toolbox provider against these old factory classes, so a newer-only
factory call cannot pass merely because the test runtime has the latest API.
Other host API classes are delegated to the normal test classloader.
