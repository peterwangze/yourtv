param([string]$Dir = ".")

$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://+:8123/")
$listener.Start()
while ($listener.IsListening) {
    $ctx = $listener.GetContext()
    $path = $ctx.Request.Url.AbsolutePath
    $file = Join-Path $Dir ("." + $path)
    if (Test-Path $file) {
        $bytes = [System.IO.File]::ReadAllBytes($file)
        $ctx.Response.StatusCode = 200
        $ctx.Response.ContentType = "audio/x-mpegurl"
        $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    } else {
        $ctx.Response.StatusCode = 404
    }
    $ctx.Response.Close()
}
