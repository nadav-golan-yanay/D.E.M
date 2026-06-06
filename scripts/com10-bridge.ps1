# DEM COM-to-UDP Bridge
# Reads MAVLink bytes from the ESP32 ground node serial port and forwards them to
# the phone's UDP input port so the phone app can relay to Mission Planner.
#
# Usage:
#   .\com10-bridge.ps1
#   .\com10-bridge.ps1 -SerialPort COM10 -PhoneHost 192.168.43.241 -PhonePort 14550
#
# The phone's UDP input port must match what you set in the DEM Telemetry Relay app.
# Phone IP on laptop hotspot: 192.168.137.241 (if laptop is the hotspot)
# Phone IP on phone hotspot:  check phone hotspot connected devices list

param(
    [string]$SerialPort = "COM10",
    [int]    $BaudRate   = 115200,
    [string]$PhoneHost  = "192.168.137.241",
    [int]    $PhonePort  = 14550,
    [int]    $LocalPort  = 14449   # UDP port to receive return commands from phone
)

Add-Type -AssemblyName System.IO.Ports

$serial = $null
$udpOut = $null
$udpIn  = $null

try {
    $serial = [System.IO.Ports.SerialPort]::new($SerialPort, $BaudRate, "None", 8, "One")
    $serial.ReadTimeout  = 50
    $serial.WriteTimeout = 200
    $serial.Open()
    Write-Host "[dem-bridge] Serial $SerialPort @ $BaudRate opened"

    $udpOut = [System.Net.Sockets.UdpClient]::new()
    $udpOut.Connect($PhoneHost, $PhonePort)
    Write-Host "[dem-bridge] UDP out -> $PhoneHost`:$PhonePort"

    $udpIn = [System.Net.Sockets.UdpClient]::new($LocalPort)
    $udpIn.Client.ReceiveTimeout = 20
    Write-Host "[dem-bridge] UDP in  <- :$LocalPort"

    Write-Host "[dem-bridge] Bridge running. Press Ctrl+C to stop."
    Write-Host ""

    $bytesSerial2Udp = 0L
    $bytesUdp2Serial = 0L
    $lastReport = [datetime]::Now
    $readBuf = [byte[]]::new(512)

    while ($true) {
        # Serial → UDP (vehicle telemetry to phone)
        try {
            $n = $serial.Read($readBuf, 0, $readBuf.Length)
            if ($n -gt 0) {
                $slice = [byte[]]::new($n)
                [Buffer]::BlockCopy($readBuf, 0, $slice, 0, $n)
                $udpOut.Send($slice, $n) | Out-Null
                $bytesSerial2Udp += $n
            }
        } catch [System.TimeoutException] { }

        # UDP → Serial (GCS commands back to FC)
        try {
            $ep   = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
            $recv = $udpIn.Receive([ref]$ep)
            if ($recv.Length -gt 0) {
                $serial.Write($recv, 0, $recv.Length)
                $bytesUdp2Serial += $recv.Length
            }
        } catch [System.Net.Sockets.SocketException] { }
          catch [System.TimeoutException] { }

        # Status report every 5 seconds
        if (([datetime]::Now - $lastReport).TotalSeconds -ge 5) {
            Write-Host "[dem-bridge] $(Get-Date -Format 'HH:mm:ss')  serial→UDP: $bytesSerial2Udp B   UDP→serial: $bytesUdp2Serial B"
            $lastReport = [datetime]::Now
        }
    }
} catch {
    Write-Host "[dem-bridge] ERROR: $_"
} finally {
    if ($serial -and $serial.IsOpen) { $serial.Close(); $serial.Dispose() }
    if ($udpOut) { $udpOut.Close() }
    if ($udpIn)  { $udpIn.Close()  }
    Write-Host "[dem-bridge] Stopped."
}
