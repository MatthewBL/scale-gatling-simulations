# Use SSH_TUNNELS env var if set, otherwise fallback to 8080-8089
$urlList = if ($env:SSH_TUNNELS) { $env:SSH_TUNNELS -split ',' } else {
    8080..8089 | ForEach-Object { "http://localhost:$_" }
}

# Extract port numbers from URLs (e.g., http://localhost:8080 -> 8080)
$ports = $urlList | ForEach-Object {
    if ($_ -match ':(\d+)$') { $matches[1] }
}

# Build -L arguments as single strings (e.g., "-L 8080:gpu05:9000")
$forwardArgs = $ports | ForEach-Object { "-L", "$_:gpu05:9000" }   # Still two elements? Wait, fix below.

# Better: create a flat list where each -L and its value are separate strings but correctly paired.
# Actually, the robust way: use a single string per forward option.
$sshOptionList = $ports | ForEach-Object { "-L $($_):gpu05:9000" }

# Add the remote host as the final argument
$allArgs = $sshOptionList + @('matbwyler@172.16.46.6')

Write-Host "Starting SSH tunnel(s) from local ports $($ports -join ', ') to gpu05:9000"
Write-Host "Press Ctrl+C to stop all tunnels."

# Invoke ssh with the argument list (splatting works because each element is a complete argument)
& 'ssh' $allArgs