Add-Type @"
using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;

public class PortBridge {
    public static void Start(int listenPort, int targetPort) {
        var listener = new TcpListener(IPAddress.Any, listenPort);
        listener.Start();
        Console.WriteLine("Bridge listening on 0.0.0.0:" + listenPort + " -> 127.0.0.1:" + targetPort);
        while (true) {
            try {
                var inClient = listener.AcceptTcpClient();
                Task.Run(async () => {
                    try {
                        using (inClient)
                        using (var outClient = new TcpClient("127.0.0.1", targetPort))
                        using (var inStream = inClient.GetStream())
                        using (var outStream = outClient.GetStream()) {
                            var t1 = inStream.CopyToAsync(outStream);
                            var t2 = outStream.CopyToAsync(inStream);
                            await Task.WhenAny(t1, t2);
                        }
                    } catch {
                        // Ignore connection errors
                    }
                });
            } catch (Exception ex) {
                Console.WriteLine("Listener exception: " + ex.Message);
            }
        }
    }
}
"@
[PortBridge]::Start(8899, 18899)
