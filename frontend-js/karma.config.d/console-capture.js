// Console capture keeps headless Chrome's async console messages flowing through
// Karma so transient browser-frame activity cannot stall the socket between tests.
config.client = config.client || {}
config.client.captureConsole = true
