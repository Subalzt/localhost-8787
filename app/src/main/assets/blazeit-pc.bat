<# : Localhost 8787 laptop helper. Double-click to run. The batch lines below hand this same file to PowerShell.
@echo off
title Localhost 8787 laptop helper
powershell -NoProfile -ExecutionPolicy Bypass -Command "$f='%~f0'; iex ([IO.File]::ReadAllText($f))"
if errorlevel 1 pause
exit /b
#>

# What this does, all on this laptop, nothing installed:
#  1. Finds the phone running Localhost 8787 on the network (and again whenever its address changes).
#  2. Serves the Localhost 8787 page at http://localhost:8787. Chrome treats localhost as secure, so
#     downloads use every connection and the clipboard works without extra clicks.
#  3. Turns the phone's Control tab into this laptop's trackpad and keyboard.
#  4. Joins the phone's direct link (its own offline Wi-Fi) when you start it, or its hotspot in
#     hotspot mode (the laptop keeps internet), and goes back to your Wi-Fi when it stops.
#  5. Switches to a USB cable whenever one is plugged in with USB tethering on: the fastest link.
#  6. Keeps the clipboard in step with the phone: text, pictures and files (the phone's Settings can turn it off).
# Starting it again, or a newer copy, closes any helper already running and takes over.
# Close this window to stop all of it.

$ErrorActionPreference = 'Stop'

# A helper started earlier, often an older version, would keep the page's address
# (localhost:8787) and its own idea of the fastest link, while this one was left on a side
# port nobody opens. So the newest copy takes over: it closes the others and their windows.
$me = Get-CimInstance Win32_Process -Filter "ProcessId=$PID"
$closed = 0
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -match '(blazeit|xoosh)-pc[^\\/'']*\.bat' -and $_.CommandLine -match 'ReadAllText' } |
    ForEach-Object {
        try {
            $window = Get-CimInstance Win32_Process -Filter "ProcessId=$($_.ParentProcessId)"
            # Its screen stream to the phone would outlive it and hold the phone's screen.
            Get-CimInstance Win32_Process -Filter "ParentProcessId=$($_.ProcessId) AND Name='ffmpeg.exe'" |
                ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            Stop-Process -Id $_.ProcessId -Force
            $closed++
            Write-Host ("Closed the Localhost 8787 helper started at " + $_.CreationDate.ToString('HH:mm') + "; this one takes over.")
            # Its window would otherwise sit at a prompt, or at "Press any key".
            if ($window -and $window.Name -eq 'cmd.exe' -and $window.ProcessId -ne $me.ParentProcessId) { Stop-Process -Id $window.ProcessId -Force }
        } catch { }
    }
if ($closed -gt 0) { Start-Sleep -Milliseconds 500 }

$source = @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

public static class BlazeItPc
{
    const int PhonePort = 8787;
    const int DiscoveryPort = 8788;
    /** Where the page is served on this laptop; the first free of 8787, 8797, 8807. */
    static int localPort = 8787;
    static readonly ManualResetEvent relayReady = new ManualResetEvent(false);

    static volatile string phone;
    /** The phone's address on the USB cable while that is the link in use; null otherwise. */
    static volatile string usbHost;
    /** The streams held open to the phone, dropped when it moves so they reconnect on the new link. */
    static volatile HttpWebRequest controlReq, eventsReq;
    /** Held for as long as this helper runs, so a second copy knows to stop. */
    static Mutex single;
    // The folder keeps the app's earlier name, so a laptop paired before the rename stays paired.
    static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Xoosh");
    static readonly string Ua = "BlazeItPC/1 (" + Environment.MachineName + ")";

    /** Where this helper's .bat file is: scrcpy may sit beside it, in a "scrcpy" folder. */
    static string HelperDir = "";

    public static void Run(string scriptPath)
    {
        try { HelperDir = Path.GetDirectoryName(scriptPath) ?? ""; } catch { }
        // .NET otherwise sends "Expect: 100-continue" with every POST body and waits for a
        // go-ahead the phone never sends, so the request times out.
        ServicePointManager.Expect100Continue = false;
        // .NET allows two connections per server by default, and two are always open to the
        // phone (the trackpad stream and the live events), so everything else would queue.
        ServicePointManager.DefaultConnectionLimit = 32;
        Directory.CreateDirectory(Dir);
        // An error nothing caught ends the helper; the log says which.
        AppDomain.CurrentDomain.UnhandledException += delegate (object o, UnhandledExceptionEventArgs e) { Log("Stopped by an error: " + e.ExceptionObject); };
        bool first;
        single = new Mutex(true, "Local\\BlazeItLaptopHelper", out first);
        if (!first)
        {
            Say("Another Localhost 8787 helper is running and could not be closed (it may be running as administrator). Close it, then start this one again.");
            Thread.Sleep(8000);
            return;
        }
        Say("Localhost 8787 laptop helper. Keep this window open; close it to stop.");
        // A helper closed while the phone was a second screen left the virtual display on the
        // desktop, with windows on it that nobody can see: take it off.
        try
        {
            if (File.Exists(HdrFile)) hdrOffHere = File.ReadAllText(HdrFile).Trim();
            if (File.Exists(ModeFile)) modeBefore = File.ReadAllText(ModeFile).Trim();
            if (File.Exists(AttachedFile)) attachedHere = File.ReadAllText(AttachedFile).Trim();
            if (hdrOffHere != null || modeBefore != null || attachedHere != null) ReleaseScreen();
        }
        catch { }
        // And closing this window does the same, rather than leave it there.
        onClose = delegate (int type)
        {
            if (type == 2 || type == 5 || type == 6) { KillStream(); ReleaseScreen(); } // close, log off, shut down
            return false;
        };
        SetConsoleCtrlHandler(onClose, true);
        // Downloaded through the phone's website: it put its name and a one-time sign-in code in
        // this file, so this laptop signs itself in without a PIN, before looking anywhere.
        string site = Environment.GetEnvironmentVariable("L87_SITE"), code = Environment.GetEnvironmentVariable("L87_CODE");
        if (!string.IsNullOrEmpty(site)) try { File.WriteAllText(SiteFile, site); } catch { }
        if (!string.IsNullOrEmpty(site) && !string.IsNullOrEmpty(code) && !File.Exists(Path.Combine(Dir, "session.txt"))) SiteEnroll(site, code);
        FindPhone(true);

        Thread relay = new Thread(RelayLoop);
        relay.IsBackground = true;
        relay.Start();

        Thread link = new Thread(LinkLoop);
        link.IsBackground = true;
        link.Start();

        Thread direct = new Thread(DirectLoop);
        direct.IsBackground = true;
        direct.Start();

        // The Windows clipboard may only be touched from a single-threaded apartment.
        Thread clip = new Thread(ClipLoop);
        clip.IsBackground = true;
        clip.SetApartmentState(ApartmentState.STA);
        clip.Start();

        Thread events = new Thread(EventsLoop);
        events.IsBackground = true;
        events.Start();

        Thread volume = new Thread(VolumeLoop);
        volume.IsBackground = true;
        volume.Start();

        Thread far = new Thread(TunnelLoop);
        far.IsBackground = true;
        far.Start();

        ControlLoop();
    }

    delegate bool ConsoleEvent(int type);
    [DllImport("kernel32.dll")] static extern bool SetConsoleCtrlHandler(ConsoleEvent handler, bool add);
    /** Kept here so the collector never frees it while Windows still holds it. */
    static ConsoleEvent onClose;

    static void Say(string s)
    {
        Console.WriteLine(DateTime.Now.ToString("HH:mm:ss") + "  " + s);
        Log(s);
    }

    static readonly object logLock = new object();

    /**
     * What the helper said and what went wrong, kept in helper.log beside its pairing, so a helper
     * that stopped leaves a reason behind. Started afresh past half a megabyte.
     */
    static void Log(string s)
    {
        try
        {
            lock (logLock)
            {
                string f = Path.Combine(Dir, "helper.log");
                if (File.Exists(f) && new FileInfo(f).Length > 512 * 1024) File.Delete(f);
                File.AppendAllText(f, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + s + Environment.NewLine);
            }
        }
        catch { }
    }

    // ------------------------------------------------------------------ finding the phone

    static bool Ping(string host)
    {
        try
        {
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://" + host + ":" + PhonePort + "/api/ping");
            r.Proxy = null;
            r.Timeout = 1500;
            r.ReadWriteTimeout = 1500;
            r.KeepAlive = false;
            using (WebResponse resp = r.GetResponse())
            using (StreamReader rd = new StreamReader(resp.GetResponseStream()))
            {
                return rd.ReadToEnd().Contains("\"ok\":true");
            }
        }
        catch { return false; }
    }

    /**
     * The phone's address over a USB cable (USB tethering), if one is plugged in: the
     * gateway of the phone's network adapter. Measured at 225-270 MB/s with a USB 3 cable,
     * several times any Wi-Fi link, so it always comes first.
     */
    static List<string> UsbGateways()
    {
        List<string> list = new List<string>();
        foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            string d = ni.Description;
            if (d.IndexOf("NDIS", StringComparison.OrdinalIgnoreCase) < 0 &&
                d.IndexOf("NCM", StringComparison.OrdinalIgnoreCase) < 0 &&
                d.IndexOf("Android", StringComparison.OrdinalIgnoreCase) < 0) continue;
            foreach (GatewayIPAddressInformation g in ni.GetIPProperties().GatewayAddresses)
                if (g.Address.AddressFamily == AddressFamily.InterNetwork) list.Add(g.Address.ToString());
        }
        return list;
    }

    /** Points everything at the phone's address on another link: a cable, a direct link, back to Wi-Fi. */
    static void MoveTo(string host)
    {
        usbHost = UsbGateways().Contains(host) ? host : null;
        if (usbHost != null) CheckCable(host);
        if (phone == host) return;
        phone = host;
        // The trackpad and event streams would otherwise stay on the old link until it times out.
        foreach (HttpWebRequest r in new HttpWebRequest[] { controlReq, eventsReq })
        {
            if (r != null) try { r.Abort(); } catch { }
        }
    }

    static string LinkName(string host)
    {
        if (host == TunnelHost) return "over the internet, through the tunnel";
        if (host == usbHost) return "over the USB cable";
        if (host == AdbHost) return "over the cable's USB debugging (turn on USB tethering on the phone for full speed)";
        if (directSsid != null) return "on the phone's " + (directSsid.StartsWith("AndroidShare") ? "direct link" : "hotspot");
        return "over Wi-Fi";
    }

    /**
     * The speed the phone's USB tethering adapter reports, in Mbps; 0 when it is not a cable.
     * Android's tethering reports a figure tied to how the USB port connected: about 426 for
     * USB 2 (which moves about 40 MB/s) and about 852 or more for USB 3 (225-270 MB/s).
     */
    static int UsbLinkMbps(string host)
    {
        foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            foreach (GatewayIPAddressInformation g in ni.GetIPProperties().GatewayAddresses)
                if (g.Address.ToString() == host) return (int)(ni.Speed / 1000000);
        }
        return 0;
    }

    /**
     * This laptop's network adapter that reaches the phone: the one with an address on the
     * phone's subnet (the cable, the phone's hotspot or direct link, or the Wi-Fi the router
     * shares with it). Its byte counters include everything on that link, not only Localhost 8787.
     */
    static NetworkInterface LinkAdapter(string host)
    {
        IPAddress target;
        if (host == null || !IPAddress.TryParse(host, out target)) return null;
        byte[] t = target.GetAddressBytes();
        foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            foreach (UnicastIPAddressInformation a in ni.GetIPProperties().UnicastAddresses)
            {
                if (a.Address.AddressFamily != AddressFamily.InterNetwork || a.IPv4Mask == null) continue;
                byte[] ip = a.Address.GetAddressBytes(), mask = a.IPv4Mask.GetAddressBytes();
                bool same = true;
                for (int i = 0; i < 4; i++) if ((ip[i] & mask[i]) != (t[i] & mask[i])) same = false;
                if (same) return ni;
            }
        }
        return null;
    }

    /** Says once per cable when it has come up at USB 2 speed, which no software can fix. */
    static string warnedSlowCable;

    static void CheckCable(string host)
    {
        int mbps = UsbLinkMbps(host);
        if (mbps <= 0 || mbps >= 600 || warnedSlowCable == host) return;
        warnedSlowCable = host;
        Say("The cable is running at USB 2 speed (about 40 MB/s). A USB 3 cable, in a USB-C port on " +
            "this laptop, gives about 250 MB/s. Charging cables are usually USB 2.");
    }

    static List<string> Candidates()
    {
        List<string> list = UsbGateways();
        string saved = Path.Combine(Dir, "phone.txt");
        if (File.Exists(saved) && !list.Contains(File.ReadAllText(saved).Trim())) list.Add(File.ReadAllText(saved).Trim());

        // On the phone's hotspot, the phone *is* the gateway.
        foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            foreach (GatewayIPAddressInformation g in ni.GetIPProperties().GatewayAddresses)
            {
                if (g.Address.AddressFamily == AddressFamily.InterNetwork && !list.Contains(g.Address.ToString()))
                    list.Add(g.Address.ToString());
            }
        }

        // Otherwise ask the network: the phone answers "XOOSH?" on UDP 8788.
        try
        {
            using (UdpClient u = new UdpClient(0))
            {
                u.EnableBroadcast = true;
                byte[] ask = Encoding.ASCII.GetBytes("XOOSH?");
                u.Send(ask, ask.Length, new IPEndPoint(IPAddress.Broadcast, DiscoveryPort));
                foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    foreach (UnicastIPAddressInformation a in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (a.Address.AddressFamily != AddressFamily.InterNetwork || a.IPv4Mask == null) continue;
                        byte[] ip = a.Address.GetAddressBytes(), mask = a.IPv4Mask.GetAddressBytes();
                        for (int i = 0; i < 4; i++) ip[i] = (byte)(ip[i] | ~mask[i]);
                        try { u.Send(ask, ask.Length, new IPEndPoint(new IPAddress(ip), DiscoveryPort)); } catch { }
                    }
                }
                u.Client.ReceiveTimeout = 1200;
                DateTime until = DateTime.Now.AddMilliseconds(1200);
                while (DateTime.Now < until)
                {
                    IPEndPoint from = new IPEndPoint(IPAddress.Any, 0);
                    byte[] got;
                    try { got = u.Receive(ref from); } catch { break; }
                    if (Encoding.UTF8.GetString(got).StartsWith("XOOSH ") && !list.Contains(from.Address.ToString()))
                        list.Add(from.Address.ToString());
                }
            }
        }
        catch { }
        // Last, the cable with USB debugging on: slower, but it needs no network at all.
        if (list.Count == 0 || !list.Exists(Ping))
        {
            string a = AdbPath();
            if (a != null && !list.Contains(a)) list.Add(a);
        }
        return list;
    }

    /** The phone over USB debugging is at this address: a relay here into adb's forward. */
    const string AdbHost = "127.0.0.2";
    const int AdbForwardPort = 18787;
    static bool adbRelay;

    /**
     * The last way in: the cable with USB debugging on, when there is no network between the two
     * (the phone on another Wi-Fi, USB tethering off). adb forwards a port on this laptop to the
     * phone's, and a relay on 127.0.0.2:8787 hands connections to it, so the phone is at an address
     * like any other. The page, the clipboard and the trackpad work; the second screen needs a
     * network. Null when adb or a phone on it is not there.
     */
    static string AdbPath()
    {
        try
        {
            string scrcpy = FindScrcpy();
            string adb = FindAdb(scrcpy != null ? Path.GetDirectoryName(scrcpy) : ScrcpyHome);
            if (!File.Exists(adb)) return null;
            if (!Regex.IsMatch(RunOut(adb, "devices"), @"\n\S+\s+device\b")) return null;
            RunOut(adb, "forward tcp:" + AdbForwardPort + " tcp:" + PhonePort);
            if (!adbRelay)
            {
                TcpListener l = new TcpListener(IPAddress.Parse(AdbHost), PhonePort);
                l.Start();
                KeepToSelf(l.Server);
                adbRelay = true;
                Thread t = new Thread(delegate ()
                {
                    while (true)
                    {
                        TcpClient c;
                        try { c = l.AcceptTcpClient(); }
                        catch (Exception e) { Log("Taking a connection over USB debugging: " + e.Message); Thread.Sleep(200); continue; }
                        Thread one = new Thread(delegate () { AdbPipe(c); });
                        one.IsBackground = true;
                        one.Start();
                    }
                });
                t.IsBackground = true;
                t.Start();
            }
            return AdbHost;
        }
        catch { return null; }
    }

    /** One connection through adb's forward, both ways. */
    static void AdbPipe(TcpClient c)
    {
        TcpClient to = new TcpClient();
        try
        {
            KeepToSelf(c.Client);
            to.NoDelay = c.NoDelay = true;
            to.Connect(IPAddress.Loopback, AdbForwardPort);
            KeepToSelf(to.Client);
            NetworkStream a = c.GetStream(), b = to.GetStream();
            Thread up = new Thread(delegate () { Copy(a, b, to); });
            up.IsBackground = true;
            up.Start();
            Copy(b, a, c);
        }
        catch { }
        finally
        {
            try { c.Close(); } catch { }
            try { to.Close(); } catch { }
        }
    }

    static void Copy(Stream from, Stream to, TcpClient closeAfter)
    {
        byte[] buf = new byte[1 << 16];
        try
        {
            int n;
            while ((n = from.Read(buf, 0, buf.Length)) > 0) to.Write(buf, 0, n);
        }
        catch { }
        try { closeAfter.Client.Shutdown(SocketShutdown.Send); } catch { }
    }

    static void FindPhone(bool firstTime)
    {
        bool told = false;
        while (true)
        {
            foreach (string c in Candidates())
            {
                if (c.Length > 0 && Ping(c))
                {
                    bool moved = phone != c;
                    MoveTo(c);
                    if (moved) Say("Found the phone at " + c + ", " + LinkName(c) + ".");
                    // The direct link's address is not where to look next time.
                    if (directSsid == null && c != AdbHost && !UsbGateways().Contains(c)) File.WriteAllText(Path.Combine(Dir, "phone.txt"), c);
                    return;
                }
            }
            // From another network, through the tunnel to the phone's saved address.
            string far = TunnelPath();
            if (far != null && Ping(far))
            {
                bool moved = phone != far;
                MoveTo(far);
                if (moved) Say("Found the phone " + LinkName(far) + ".");
                return;
            }
            // Closed last time while on a direct link that has since ended: go home first.
            if (File.Exists(DirectFile))
            {
                string[] rec = File.ReadAllText(DirectFile).Split('\n');
                string was = rec[0].Trim();
                bool added = rec.Length >= 2 && rec[1].Trim() == "added";
                string home = File.Exists(HomeFile) ? File.ReadAllText(HomeFile).Trim() : "";
                try { File.Delete(DirectFile); } catch { }
                Say("Going back to " + (home.Length > 0 ? home : "your Wi-Fi") + " from an earlier direct link.");
                if (home.Length > 0) RunNetsh("wlan connect name=\"" + home + "\"");
                if (was.Length > 0 && added && was.StartsWith("AndroidShare")) RunNetsh("wlan delete profile name=\"" + was + "\"");
                Thread.Sleep(4000);
                continue;
            }
            // The question waits for an answer on its own thread: the search goes on meanwhile, and a
            // phone that turns up (plugged in, switched on) is found without anyone pressing a key.
            string typed = firstTime ? TakeTyped() : null;
            if (firstTime && typed == null)
            {
                string known = File.Exists(SiteFile) ? File.ReadAllText(SiteFile).Trim() : "";
                AskOnce("Could not find the phone; still looking. Type the address it shows, or its website (" +
                    (known.Length > 0 ? known : "yourname.dynv6.net") + ") to ask the phone from anywhere: ");
                told = true;
            }
            if (typed != null)
            {
                // The phone's website: ask the phone through it, and get the tunnel's keys that way.
                string site = Regex.Replace(typed.ToLowerInvariant(), "^https?://|[:/].*$", "");
                if (Regex.IsMatch(site, @"^[a-z0-9-]+(\.[a-z0-9-]+)+$") && Regex.IsMatch(site, "[a-z]"))
                {
                    SiteSignIn(site);
                    continue;
                }
                // The IPv6 address on the phone's Home: where the tunnel finds it now.
                Match v6 = Regex.Match(typed, @"([0-9a-fA-F]{1,4}(:[0-9a-fA-F]{0,4}){2,7})");
                if (v6.Success && typed.Contains(":"))
                {
                    List<string> addrs = TunnelAddrs(TunnelConf());
                    addrs.Remove(v6.Groups[1].Value);
                    addrs.Insert(0, v6.Groups[1].Value);
                    if (TunnelConf() == null) Say("This laptop has not been paired with the phone yet: pair once on the same network first.");
                    TunnelSaveAddrs(addrs);
                    continue;
                }
                Match m = Regex.Match(typed, @"(\d{1,3}(\.\d{1,3}){3})");
                if (m.Success && Ping(m.Groups[1].Value))
                {
                    MoveTo(m.Groups[1].Value);
                    File.WriteAllText(Path.Combine(Dir, "phone.txt"), phone);
                    Say("Connected to " + phone + ".");
                    return;
                }
            }
            else if (!told)
            {
                Say("Waiting for the phone. Start Localhost 8787 on it, or check both are on the same Wi-Fi.");
                told = true;
            }
            Thread.Sleep(2000);
        }
    }

    // ------------------------------------------------------------------ typed answers

    static Thread asking;
    static string typedLine;
    static readonly object typedLock = new object();

    /** Asks once, and reads the answer on its own thread; the question is not asked again while it waits. */
    static void AskOnce(string question)
    {
        lock (typedLock)
        {
            if (asking != null && asking.IsAlive) return;
            Console.Write(question);
            asking = new Thread(delegate ()
            {
                string line = Console.ReadLine();
                lock (typedLock) { typedLine = (line ?? "").Trim(); Monitor.PulseAll(typedLock); }
            });
            asking.IsBackground = true;
            asking.Start();
        }
    }

    /** What was typed since the last look, or null. */
    static string TakeTyped()
    {
        lock (typedLock) { string t = typedLine; typedLine = null; return t; }
    }

    /** A line from the console: the one the waiting question gets, if one is waiting. */
    static string ReadLineShared()
    {
        lock (typedLock)
        {
            if (asking != null && asking.IsAlive)
            {
                while (typedLine == null) Monitor.Wait(typedLock);
                string t = typedLine; typedLine = null; return t;
            }
        }
        return (Console.ReadLine() ?? "").Trim();
    }

    // ------------------------------------------------------------------ pairing

    /**
     * From anywhere: signs in through the phone's website (https://NAME:8443, a real certificate,
     * so this is the phone and nobody reads along) with the PIN set on the phone, as a browser does,
     * and keeps the session and the tunnel's keys; from then on the tunnel finds the phone by itself.
     */
    static readonly string SiteFile = Path.Combine(Dir, "site.txt");

    /** Keeps what a sign-in through the website gave: the session, and the tunnel's keys. */
    static void SiteKeep(string name, string cookie, string conf)
    {
        if (!conf.Contains("\"key\"")) throw new Exception("the phone gave no tunnel keys");
        File.WriteAllText(Path.Combine(Dir, "session.txt"), cookie);
        File.WriteAllText(TunnelFile, conf);
        File.WriteAllText(SiteFile, name);
    }

    /** With the one-time code baked into a helper downloaded through the website: no PIN. */
    static bool SiteEnroll(string name, string code)
    {
        try
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create("https://" + name + ":8443/api/site/enroll");
            r.Method = "POST";
            r.UserAgent = Ua;
            r.Timeout = 20000;
            r.ContentType = "application/json";
            byte[] body = Encoding.UTF8.GetBytes("{\"code\":\"" + code + "\"}");
            r.ContentLength = body.Length;
            using (Stream w = r.GetRequestStream()) w.Write(body, 0, body.Length);
            using (HttpWebResponse resp = (HttpWebResponse)r.GetResponse())
            {
                Match m = Regex.Match(resp.Headers["Set-Cookie"] ?? "", "xoosh_session=([^;,\\s]+)");
                if (!m.Success) throw new Exception("the phone sent no session");
                SiteKeep(name, "xoosh_session=" + m.Groups[1].Value, Body(resp));
            }
            Say("Signed in to the phone through " + name + ". This laptop finds it by itself from now on: the cable or Wi-Fi when it is close, the internet when it is not.");
            return true;
        }
        catch (WebException e)
        {
            string why = "";
            if (e.Response != null) using (HttpWebResponse resp = (HttpWebResponse)e.Response) why = Regex.Match(Body(resp), "\"message\"\\s*:\\s*\"([^\"]*)\"").Groups[1].Value;
            Say(why.Length > 0 ? why : "Could not sign in through " + name + " (" + e.Message + ").");
            return false;
        }
        catch (Exception e)
        {
            Say("Could not sign in through " + name + " (" + e.Message + ").");
            return false;
        }
    }

    /**
     * From anywhere: asks the phone through its website (https://NAME:8443, a real certificate, so
     * this is the phone and nobody reads along), as a browser there does; on Allow on the phone,
     * keeps the session and the tunnel's keys, and from then on the tunnel finds the phone by itself.
     */
    static bool SiteSignIn(string name)
    {
        string bas = "https://" + name + ":8443";
        try
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            string id, code;
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create(bas + "/api/pair");
            r.Method = "POST";
            r.UserAgent = Ua;
            r.Timeout = 15000;
            r.ContentLength = 0;
            try
            {
                using (HttpWebResponse resp = (HttpWebResponse)r.GetResponse())
                {
                    string b = Body(resp);
                    id = Regex.Match(b, "\"id\":\"([^\"]+)\"").Groups[1].Value;
                    code = Regex.Match(b, "\"code\":\"([^\"]+)\"").Groups[1].Value;
                }
            }
            catch (WebException e)
            {
                if (e.Response == null) throw;
                string why;
                using (HttpWebResponse resp = (HttpWebResponse)e.Response) why = Regex.Match(Body(resp), "\"message\"\\s*:\\s*\"([^\"]*)\"").Groups[1].Value;
                Say(why.Length > 0 ? why : "The phone refused to ask.");
                return false;
            }
            Say("On the phone, allow \"Laptop control on " + Environment.MachineName + " (website)\". Code: " + code);
            for (int i = 0; i < 125; i++)
            {
                Thread.Sleep(1000);
                HttpWebRequest p = (HttpWebRequest)WebRequest.Create(bas + "/api/pair/" + id);
                p.UserAgent = Ua;
                p.Timeout = 15000;
                string b, set;
                try { using (HttpWebResponse resp = (HttpWebResponse)p.GetResponse()) { b = Body(resp); set = resp.Headers["Set-Cookie"] ?? ""; } }
                catch (WebException) { continue; }
                if (b.Contains("DENIED")) { Say("The phone said no."); return false; }
                if (b.Contains("EXPIRED")) { Say("Nobody answered on the phone."); return false; }
                if (!b.Contains("APPROVED")) continue;
                Match m = Regex.Match(set, "xoosh_session=([^;,\\s]+)");
                if (!m.Success) throw new Exception("the phone approved but sent no session");
                string cookie = "xoosh_session=" + m.Groups[1].Value;
                HttpWebRequest t = (HttpWebRequest)WebRequest.Create(bas + "/api/tunnel");
                t.UserAgent = Ua;
                t.Timeout = 15000;
                t.Headers["Cookie"] = cookie;
                string conf;
                using (HttpWebResponse resp = (HttpWebResponse)t.GetResponse()) conf = Body(resp);
                SiteKeep(name, cookie, conf);
                Say("Allowed through " + name + ". This laptop finds the phone by itself from now on.");
                return true;
            }
            Say("Nobody answered on the phone.");
            return false;
        }
        catch (Exception e)
        {
            Say("Could not reach the phone at " + bas + " (" + e.Message + "). The website needs IPv6 on this network; " +
                "or pair once on the same network instead.");
            return false;
        }
    }

    static HttpWebResponse Http(string method, string path, string cookie, int timeoutMs)
    {
        HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://" + phone + ":" + PhonePort + path);
        r.Method = method;
        r.Proxy = null;
        r.UserAgent = Ua;
        r.Timeout = timeoutMs;
        r.ReadWriteTimeout = timeoutMs;
        r.KeepAlive = false;
        if (cookie != null) r.Headers["Cookie"] = cookie;
        if (method == "POST") r.ContentLength = 0;
        try { return (HttpWebResponse)r.GetResponse(); }
        catch (WebException e)
        {
            if (e.Response != null) return (HttpWebResponse)e.Response;
            throw;
        }
    }

    static string Body(HttpWebResponse r)
    {
        using (StreamReader rd = new StreamReader(r.GetResponseStream())) return rd.ReadToEnd();
    }

    static string Pair()
    {
        while (true)
        {
            string id, code;
            using (HttpWebResponse r = Http("POST", "/api/pair", null, 5000))
            {
                string b = Body(r);
                if ((int)r.StatusCode == 429) { Say("The phone is busy with other requests; trying again shortly."); Thread.Sleep(5000); continue; }
                id = Regex.Match(b, "\"id\":\"([^\"]+)\"").Groups[1].Value;
                code = Regex.Match(b, "\"code\":\"([^\"]+)\"").Groups[1].Value;
            }
            Say("On the phone, allow \"Laptop control on " + Environment.MachineName + "\". Code: " + code);
            for (int i = 0; i < 125; i++)
            {
                Thread.Sleep(1000);
                using (HttpWebResponse r = Http("GET", "/api/pair/" + id, null, 5000))
                {
                    string b = Body(r);
                    if (b.Contains("APPROVED"))
                    {
                        string set = r.Headers["Set-Cookie"] ?? "";
                        Match m = Regex.Match(set, "xoosh_session=([^;,\\s]+)");
                        if (!m.Success) throw new Exception("the phone approved but sent no session");
                        string cookie = "xoosh_session=" + m.Groups[1].Value;
                        File.WriteAllText(Path.Combine(Dir, "session.txt"), cookie);
                        Say("Allowed. This laptop will not need to ask again.");
                        return cookie;
                    }
                    if (b.Contains("DENIED"))
                    {
                        Say("The phone said no. Press Enter to ask again.");
                        ReadLineShared();
                        break;
                    }
                    if (b.Contains("EXPIRED")) break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ link report
    //
    // Every two seconds: this laptop's side of the Wi-Fi link (signal, link rates, channel,
    // band, generation) and the round trip to the phone, for the phone's Monitor tab.

    static volatile string session;

    static void LinkLoop()
    {
        while (true)
        {
            // Every 2 s while someone has the monitor open; every 15 s otherwise, which is
            // only there to notice when someone opens it.
            int wait = 15000;
            try
            {
                string cookie = session;
                if (phone != null && cookie != null)
                {
                    Dictionary<string, string> kv = Netsh();
                    Stopwatch sw = Stopwatch.StartNew();
                    int rtt = Ping(phone) ? (int)sw.ElapsedMilliseconds : -1;
                    // All traffic on the adapter that reaches the phone; the phone subtracts its
                    // own to show what else (this laptop's internet, other apps) shares the link.
                    NetworkInterface via = LinkAdapter(phone);
                    long rx = 0, tx = 0;
                    if (via != null)
                    {
                        try { IPInterfaceStatistics st = via.GetIPStatistics(); rx = st.BytesReceived; tx = st.BytesSent; } catch { }
                    }
                    string json = "{\"ssid\":" + Q(Get(kv, "SSID")) +
                        ",\"signalPercent\":" + Num(Get(kv, "Signal")) +
                        ",\"rxMbps\":" + Num(Get(kv, "Receive rate (Mbps)")) +
                        ",\"txMbps\":" + Num(Get(kv, "Transmit rate (Mbps)")) +
                        ",\"channel\":" + Q(Get(kv, "Channel")) +
                        ",\"band\":" + Q(Get(kv, "Band")) +
                        ",\"radio\":" + Q(Radio(Get(kv, "Radio type"))) +
                        ",\"rttMs\":" + rtt +
                        ",\"usbMbps\":" + (usbHost != null && phone == usbHost ? UsbLinkMbps(usbHost) : 0) +
                        ",\"iface\":" + Q(via != null ? via.Id : "") +
                        ",\"rxBytes\":" + rx + ",\"txBytes\":" + tx + "}";
                    HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://" + phone + ":" + PhonePort + "/api/monitor/link");
                    r.Method = "POST";
                    r.Proxy = null;
                    r.UserAgent = Ua;
                    r.Timeout = 3000;
                    r.KeepAlive = false;
                    r.ContentType = "application/json";
                    r.Headers["Cookie"] = cookie;
                    byte[] body = Encoding.UTF8.GetBytes(json);
                    r.ContentLength = body.Length;
                    using (Stream o = r.GetRequestStream()) o.Write(body, 0, body.Length);
                    using (WebResponse resp = r.GetResponse())
                    using (StreamReader rd = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                    {
                        if (rd.ReadToEnd().Contains("\"watch\"")) wait = 2000;
                    }
                }
            }
            catch { }
            Thread.Sleep(wait);
        }
    }

    /** The "Name : value" lines of netsh's description of this Wi-Fi connection. */
    static Dictionary<string, string> Netsh()
    {
        Dictionary<string, string> kv = new Dictionary<string, string>();
        ProcessStartInfo psi = new ProcessStartInfo("netsh", "wlan show interfaces");
        psi.UseShellExecute = false;
        psi.RedirectStandardOutput = true;
        psi.CreateNoWindow = true;
        using (Process pr = Process.Start(psi))
        {
            string text = pr.StandardOutput.ReadToEnd();
            pr.WaitForExit(2000);
            foreach (string raw in text.Split('\n'))
            {
                int colon = raw.IndexOf(" : ");
                if (colon < 0) continue;
                string key = raw.Substring(0, colon).Trim();
                if (!kv.ContainsKey(key)) kv[key] = raw.Substring(colon + 3).Trim();
            }
        }
        return kv;
    }

    static string Get(Dictionary<string, string> kv, string k) { string v; return kv.TryGetValue(k, out v) ? v : ""; }

    static string Num(string v)
    {
        StringBuilder b = new StringBuilder();
        foreach (char c in v) { if (char.IsDigit(c)) b.Append(c); else if (b.Length > 0) break; }
        return b.Length > 0 ? b.ToString() : "0";
    }

    static string Q(string v) { return "\"" + v.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\""; }

    static string Radio(string r)
    {
        if (r.EndsWith("be")) return "Wi-Fi 7";
        if (r.EndsWith("ax")) return "Wi-Fi 6";
        if (r.EndsWith("ac")) return "Wi-Fi 5";
        if (r.EndsWith("n")) return "Wi-Fi 4";
        return r;
    }

    // ------------------------------------------------------------------ direct link and hotspot
    //
    // When the phone offers a fast link for laptops, this laptop joins it: either the
    // phone's direct link (its own offline network, fastest) or, in hotspot mode, the
    // phone's ordinary hotspot, which shares the phone's internet so this laptop stays
    // online. The page keeps working because it talks to localhost, and the relay follows
    // the phone to its address on the new network. When the link goes away, the laptop goes
    // back to the Wi-Fi it was on. A link started only for a phone-to-phone send says so
    // ("laptop":false), and this laptop stays where it is.

    /** The direct link's network name while this laptop is on it; null otherwise. */
    static volatile string directSsid;
    /** The Wi-Fi profile to go back to. */
    static string homeProfile;
    /** True when this helper created the profile it joined, so it may delete it afterwards. */
    static bool addedProfile;
    /** A network that could not be joined, so it is not retried every few seconds. */
    static string gaveUpOn;

    static string DirectFile { get { return Path.Combine(Dir, "direct-wifi.txt"); } }
    static string HomeFile { get { return Path.Combine(Dir, "home-wifi.txt"); } }

    static void DirectLoop()
    {
        if (File.Exists(HomeFile)) homeProfile = File.ReadAllText(HomeFile).Trim();
        if (File.Exists(DirectFile))
        {
            string[] rec = File.ReadAllText(DirectFile).Split('\n');
            directSsid = rec[0].Trim();
            // Only a profile this helper recorded as its own may be deleted; older helpers
            // wrote one line, and a profile of unknown origin is always kept.
            addedProfile = rec.Length >= 2 && rec[1].Trim() == "added";
        }
        int misses = 0, usbMisses = 0;
        while (true)
        {
            Thread.Sleep(2500);
            try
            {
                string cookie = session;
                if (cookie == null || phone == null) continue;

                // A USB cable beats every Wi-Fi link: switch to it, and leave the direct link.
                string usb = null;
                foreach (string g in UsbGateways()) { if (Ping(g)) { usb = g; break; } }
                if (usb != null)
                {
                    usbMisses = 0;
                    if (directSsid != null) LeaveDirect();
                    if (phone != usb)
                    {
                        MoveTo(usb);
                        Say("USB cable to the phone found: using it. It is several times faster than any Wi-Fi link.");
                    }
                    continue;
                }
                // Unplugged, or USB tethering switched off: find the phone over the air again. A
                // cable still listed gets one more look, in case the phone was only slow to answer.
                if (usbHost != null && phone == usbHost &&
                    (!UsbGateways().Contains(usbHost) || ++usbMisses >= 2))
                {
                    usbMisses = 0;
                    Say("The USB cable is gone; looking for the phone over Wi-Fi.");
                    usbHost = null;
                    FindPhone(false);
                    continue;
                }
                string body = null;
                try { using (HttpWebResponse r = Http("GET", "/api/direct", cookie, 3000)) body = Body(r); } catch { }
                if (body == null)
                {
                    // On the link and the phone has gone quiet: the link was stopped.
                    if (directSsid != null && ++misses >= 3) { LeaveDirect(); misses = 0; }
                    continue;
                }
                misses = 0;
                bool on = body.Contains("\"state\":\"on\"") && !body.Contains("\"laptop\":false");
                string ssid = Field(body, "ssid"), host = Field(body, "host");
                if (on && ssid.Length > 0 && host.Length > 0 && directSsid != ssid && gaveUpOn != ssid)
                    JoinDirect(ssid, Field(body, "passphrase"), host, Field(body, "security"), Field(body, "kind") == "hotspot");
                else if (!on && directSsid != null) LeaveDirect();
                if (!on) gaveUpOn = null;
            }
            catch (Exception e) { Say("Direct link: " + e.Message); }
        }
    }

    static string Field(string json, string key)
    {
        Match m = Regex.Match(json, "\"" + key + "\":\"((?:[^\"\\\\]|\\\\.)*)\"");
        return m.Success ? Regex.Unescape(m.Groups[1].Value) : "";
    }

    static string Xml(string s) { return System.Security.SecurityElement.Escape(s); }

    static string RunNetsh(string args)
    {
        ProcessStartInfo psi = new ProcessStartInfo("netsh", args);
        psi.UseShellExecute = false;
        psi.RedirectStandardOutput = true;
        psi.CreateNoWindow = true;
        using (Process pr = Process.Start(psi))
        {
            string text = pr.StandardOutput.ReadToEnd();
            pr.WaitForExit(10000);
            return text;
        }
    }

    static void JoinDirect(string ssid, string pass, string host, string security, bool hotspot)
    {
        // A network this laptop already knows (the phone's hotspot, often) is joined with the
        // saved profile, which is left exactly as it was.
        bool known = !RunNetsh("wlan show profile name=\"" + ssid + "\"").Contains("is not found");
        if (!known && pass.Length == 0)
        {
            Say("The phone's hotspot is on, but this laptop does not know its password. Add it in the phone's Settings (Laptop link).");
            gaveUpOn = ssid;
            return;
        }
        string current = Get(Netsh(), "Profile");
        if (current.Length > 0 && current != ssid)
        {
            homeProfile = current;
            File.WriteAllText(HomeFile, current);
        }
        Say(hotspot
            ? "The phone's hotspot is on. Moving this laptop onto it; the internet stays on through the phone."
            : "The phone started its direct link. Moving this laptop onto it; there is no internet while on it.");
        addedProfile = !known;
        if (known) { JoinAndFind(ssid, host, hotspot); return; }
        string xml = "<?xml version=\"1.0\"?><WLANProfile xmlns=\"http://www.microsoft.com/networking/WLAN/profile/v1\">" +
            "<name>" + Xml(ssid) + "</name><SSIDConfig><SSID><name>" + Xml(ssid) + "</name></SSID></SSIDConfig>" +
            "<connectionType>ESS</connectionType><connectionMode>manual</connectionMode><MSM><security>" +
            "<authEncryption><authentication>" + (security == "WPA3" ? "WPA3SAE" : "WPA2PSK") + "</authentication>" +
            "<encryption>AES</encryption><useOneX>false</useOneX></authEncryption>" +
            "<sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>" + Xml(pass) + "</keyMaterial></sharedKey>" +
            "</security></MSM></WLANProfile>";
        string file = Path.Combine(Path.GetTempPath(), "blazeit-direct.xml");
        try
        {
            File.WriteAllText(file, xml);
            RunNetsh("wlan add profile filename=\"" + file + "\" user=current");
        }
        finally { try { File.Delete(file); } catch { } }
        JoinAndFind(ssid, host, hotspot);
    }

    static void JoinAndFind(string ssid, string host, bool hotspot)
    {
        directSsid = ssid;
        File.WriteAllText(DirectFile, ssid + "\n" + (addedProfile ? "added" : "saved"));
        RunNetsh("wlan connect name=\"" + ssid + "\" ssid=\"" + ssid + "\"");
        for (int i = 0; i < 50; i++)
        {
            Thread.Sleep(500);
            if (Ping(host))
            {
                MoveTo(host);
                Say(hotspot ? "On the phone's hotspot. The page carries on over it, and the internet works."
                            : "On the direct link. The page carries on over it at full speed.");
                return;
            }
        }
        Say("Could not reach the phone on " + (hotspot ? "its hotspot" : "its direct link") + "; going back.");
        gaveUpOn = ssid;
        LeaveDirect();
    }

    static void LeaveDirect()
    {
        string ssid = directSsid;
        directSsid = null;
        try { File.Delete(DirectFile); } catch { }
        if (!string.IsNullOrEmpty(homeProfile)) RunNetsh("wlan connect name=\"" + homeProfile + "\"");
        // Only a network this helper added itself (the direct link's, never one you saved) goes.
        if (ssid != null && addedProfile && ssid.StartsWith("AndroidShare")) RunNetsh("wlan delete profile name=\"" + ssid + "\"");
        addedProfile = false;
        Say("The phone's link ended. Back on " + (string.IsNullOrEmpty(homeProfile) ? "your Wi-Fi" : homeProfile) + ".");
        Thread.Sleep(3000);
        FindPhone(false);
    }

    // ------------------------------------------------------------------ clipboard sync
    //
    // One clipboard for this laptop and the phone. Copy text, a picture (a screenshot, say) or
    // a single file up to 50 MB here and it is on the phone, ready to paste; copy on the phone
    // and it lands here (the phone sends it when Localhost 8787 opens or its tile is tapped): a picture
    // pastes into apps and as a file into a folder, a file pastes into a folder. The phone's
    // Settings can turn it off.

    [DllImport("user32.dll")]
    static extern uint GetClipboardSequenceNumber();

    /** Something to put on this laptop's clipboard: text, or a picture or file downloaded from the phone. */
    class ClipSet { public string text; public string path; public bool image; }

    static readonly Queue<ClipSet> clipToSet = new Queue<ClipSet>();
    /** The last text seen on, or put on, this laptop's clipboard; never sent back. */
    static volatile string lastClip;
    static volatile bool clipSync = true;
    /** The version of the last picture or file that went either way, so none goes back and forth. */
    static long lastBlobV = -1;
    /** A fingerprint of the last picture or file sent from here, so re-copying it sends nothing. */
    static string lastBlobSig;
    /** The clipboard's sequence number just after this helper set it: what "Clear" may undo. */
    static uint ourSeq;
    /** True while a picture or file is going up: the change the phone announces meanwhile is that one. */
    static volatile bool blobInFlight;
    const long ClipMaxBytes = 50L * 1024 * 1024;
    static readonly string ClipDir = Path.Combine(Path.GetTempPath(), "BlazeIt clipboard");

    static void ClipLoop()
    {
        uint seq = GetClipboardSequenceNumber();
        while (true)
        {
            Thread.Sleep(350);
            try
            {
                ClipSet pending = null;
                lock (clipToSet) { if (clipToSet.Count > 0) pending = clipToSet.Dequeue(); }
                if (pending != null)
                {
                    for (int i = 0; i < 5; i++)
                    {
                        try { ApplyClip(pending); break; }
                        catch { Thread.Sleep(120); } // another app has the clipboard open
                    }
                    if (pending.text != null) lastClip = pending.text;
                    seq = ourSeq = GetClipboardSequenceNumber();
                    continue;
                }
                uint now = GetClipboardSequenceNumber();
                if (now == seq) continue;
                seq = now;
                if (!clipSync || session == null || phone == null) continue;
                var cb = System.Windows.Forms.Clipboard.GetDataObject();
                if (cb == null) continue;
                if (System.Windows.Forms.Clipboard.ContainsText())
                {
                    string text = null;
                    try { text = System.Windows.Forms.Clipboard.GetText(); } catch { }
                    if (string.IsNullOrEmpty(text) || text == lastClip || text.Length > 200000) continue;
                    lastClip = text;
                    SendClip(text);
                }
                else if (System.Windows.Forms.Clipboard.ContainsFileDropList())
                {
                    var files = System.Windows.Forms.Clipboard.GetFileDropList();
                    // One file goes on the shared clipboard; a folder or several files are for Send files.
                    if (files.Count != 1 || !File.Exists(files[0])) continue;
                    var fi = new FileInfo(files[0]);
                    if (fi.Length > ClipMaxBytes) { Say("That file is over 50 MB, too big for the clipboard; send it from the page instead."); continue; }
                    string sig = fi.FullName + "|" + fi.Length + "|" + fi.LastWriteTimeUtc.Ticks;
                    if (sig == lastBlobSig) continue;
                    lastBlobSig = sig;
                    SendClipBlob(File.ReadAllBytes(fi.FullName), fi.Name, MimeOf(fi.Name));
                }
                else if (System.Windows.Forms.Clipboard.ContainsImage())
                {
                    byte[] png;
                    using (var img = System.Windows.Forms.Clipboard.GetImage())
                    using (var ms = new MemoryStream())
                    {
                        if (img == null) continue;
                        img.Save(ms, System.Drawing.Imaging.ImageFormat.Png);
                        png = ms.ToArray();
                    }
                    string sig;
                    using (var md5 = System.Security.Cryptography.MD5.Create()) sig = Convert.ToBase64String(md5.ComputeHash(png));
                    if (sig == lastBlobSig) continue;
                    lastBlobSig = sig;
                    SendClipBlob(png, "Picture " + DateTime.Now.ToString("yyyy-MM-dd HHmmss") + ".png", "image/png");
                }
            }
            catch { }
        }
    }

    static void SendClip(string text)
    {
        try
        {
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://" + phone + ":" + PhonePort + "/api/clipboard");
            r.Method = "POST";
            r.Proxy = null;
            r.UserAgent = Ua;
            r.Timeout = 4000;
            r.KeepAlive = false;
            r.ContentType = "application/json";
            r.Headers["Cookie"] = session;
            r.Headers["Bridge-Auto"] = "1";
            byte[] body = Encoding.UTF8.GetBytes("{\"text\":" + Json(text) + "}");
            r.ContentLength = body.Length;
            using (Stream o = r.GetRequestStream()) o.Write(body, 0, body.Length);
            using (WebResponse resp = r.GetResponse()) { }
        }
        catch { }
    }

    /** Puts text, or a downloaded picture or file, on this laptop's clipboard. */
    static void ApplyClip(ClipSet c)
    {
        if (c.text != null) { System.Windows.Forms.Clipboard.SetText(c.text); return; }
        if (c.path == null)
        {
            // Cleared on the phone or the page: only what the sync itself put here is removed.
            if (GetClipboardSequenceNumber() == ourSeq) System.Windows.Forms.Clipboard.Clear();
            return;
        }
        var data = new System.Windows.Forms.DataObject();
        var list = new System.Collections.Specialized.StringCollection();
        list.Add(c.path);
        // A file pastes into a folder; a picture also pastes into Paint, chat apps and documents.
        data.SetFileDropList(list);
        if (c.image)
        {
            try
            {
                using (var fs = new FileStream(c.path, FileMode.Open, FileAccess.Read))
                using (var img = System.Drawing.Image.FromStream(fs))
                    data.SetImage(new System.Drawing.Bitmap(img));
            }
            catch { } // a format Windows cannot draw (HEIC, say) still pastes as a file
        }
        System.Windows.Forms.Clipboard.SetDataObject(data, true);
    }

    static string MimeOf(string name)
    {
        switch (Path.GetExtension(name).ToLowerInvariant())
        {
            case ".png": return "image/png";
            case ".jpg": case ".jpeg": return "image/jpeg";
            case ".gif": return "image/gif";
            case ".webp": return "image/webp";
            case ".bmp": return "image/bmp";
            case ".heic": return "image/heic";
            case ".pdf": return "application/pdf";
            case ".txt": return "text/plain";
            case ".zip": return "application/zip";
            case ".docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".mp4": return "video/mp4";
            case ".mp3": return "audio/mpeg";
            default: return "application/octet-stream";
        }
    }

    /** A picture or file onto the shared clipboard, and so onto the phone's. */
    static void SendClipBlob(byte[] body, string name, string mime)
    {
        try
        {
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://" + phone + ":" + PhonePort +
                "/api/clipboard/blob?name=" + Uri.EscapeDataString(name));
            r.Method = "POST";
            r.Proxy = null;
            r.UserAgent = Ua;
            r.Timeout = 30000;
            r.ReadWriteTimeout = 30000;
            r.KeepAlive = false;
            r.ContentType = mime;
            r.Headers["Cookie"] = session;
            r.Headers["Bridge-Auto"] = "1";
            r.ContentLength = body.Length;
            blobInFlight = true;
            using (Stream o = r.GetRequestStream()) o.Write(body, 0, body.Length);
            using (HttpWebResponse resp = (HttpWebResponse)r.GetResponse())
            {
                Match m = Regex.Match(Body(resp), "\"v\":(\\d+)");
                if (m.Success) lastBlobV = long.Parse(m.Groups[1].Value);
            }
            Say((mime.StartsWith("image/") ? "Picture" : "File") + " copied here is on the phone's clipboard.");
        }
        catch (Exception e) { Say("Could not put it on the phone's clipboard (" + e.Message + ")."); }
        finally { blobInFlight = false; }
    }

    /** Downloads the picture or file on the shared clipboard and queues it for this laptop's. */
    static void FetchClipBlob(long v, string name, bool image)
    {
        try
        {
            Directory.CreateDirectory(ClipDir);
            foreach (string old in Directory.GetFiles(ClipDir)) { try { File.Delete(old); } catch { } }
            foreach (char ch in Path.GetInvalidFileNameChars()) name = name.Replace(ch, '_');
            if (name.Length == 0) name = image ? "Picture.png" : "File";
            string path = Path.Combine(ClipDir, name);
            using (HttpWebResponse resp = Http("GET", "/api/clipboard/blob?v=" + v, session, 30000))
            using (Stream s = resp.GetResponseStream())
            using (FileStream f = File.Create(path))
                s.CopyTo(f);
            lock (clipToSet) clipToSet.Enqueue(new ClipSet { path = path, image = image });
        }
        catch { }
    }

    static string Json(string v)
    {
        StringBuilder b = new StringBuilder("\"");
        foreach (char c in v)
        {
            if (c == '"') b.Append("\\\"");
            else if (c == '\\') b.Append("\\\\");
            else if (c == '\n') b.Append("\\n");
            else if (c == '\r') b.Append("\\r");
            else if (c == '\t') b.Append("\\t");
            else if (c < ' ') b.Append("\\u").Append(((int)c).ToString("x4"));
            else b.Append(c);
        }
        return b.Append('"').ToString();
    }

    /**
     * The phone's live event stream: the phone's clipboard, and the sync switch. The first
     * clipboard event after connecting is only the phone's stored text, so it never
     * overwrites this laptop's clipboard; only changes after that do.
     */
    static void EventsLoop()
    {
        while (true)
        {
            try
            {
                string cookie = session, at = phone;
                if (cookie == null || at == null) { Thread.Sleep(2000); continue; }
                using (HttpWebResponse st = Http("GET", "/api/state", cookie, 5000))
                    clipSync = !Body(st).Contains("\"clipSync\":false");
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create("http://" + at + ":" + PhonePort + "/events");
                req.Proxy = null;
                req.UserAgent = Ua;
                req.Timeout = 5000;
                req.ReadWriteTimeout = 40000; // the phone sends a heartbeat every 15 s
                req.Headers["Cookie"] = cookie;
                eventsReq = req;
                using (WebResponse resp = req.GetResponse())
                using (StreamReader rd = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                {
                    string ev = null, line;
                    StringBuilder data = new StringBuilder();
                    bool snapshot = true, clipSnapshot = true;
                    while ((line = rd.ReadLine()) != null)
                    {
                        if (phone != at) break; // moved to another link (cable, direct link): reconnect there
                        if (line.Length == 0)
                        {
                            string d = data.ToString();
                            if (ev == "clipsync") clipSync = d == "on";
                            else if (ev == "display")
                            {
                                string[] p = d.Split(' ');
                                if (p[0] == "start" && p.Length >= 4)
                                {
                                    int port = int.Parse(p[1]), w = int.Parse(p[2]), h = int.Parse(p[3]);
                                    // Newer phones add their refresh rate and decoder throughput.
                                    phoneHz = p.Length >= 5 ? Math.Max(30, int.Parse(p[4])) : 60;
                                    long budget = p.Length >= 6 ? long.Parse(p[5]) : 0;
                                    phoneBlocks = budget > 0 ? budget : 2073600;
                                    // Newer phones take the stream on the page's port, so it goes the way
                                    // everything else does, the tunnel included.
                                    bool http = p.Length >= 7 && p[6] == "http";
                                    Thread s = new Thread(delegate () { StartSecondScreen(at, port, w, h, http); });
                                    s.IsBackground = true;
                                    s.Start();
                                }
                                else StopSecondScreen();
                            }
                            else if (ev == "mirror" && !snapshot)
                            {
                                Thread m = new Thread(delegate () { OpenPhoneScreen(); });
                                m.IsBackground = true;
                                m.Start();
                            }
                            else if (ev == "clip")
                            {
                                string kind = Field(d, "kind");
                                Match mv = Regex.Match(d, "\"v\":(\\d+)");
                                long v = mv.Success ? long.Parse(mv.Groups[1].Value) : -1;
                                if (clipSnapshot) { clipSnapshot = false; lastBlobV = v; }
                                else if (blobInFlight) lastBlobV = v; // our own upload, announced back
                                else if (clipSync && v != lastBlobV)
                                {
                                    lastBlobV = v;
                                    if (kind == "image" || kind == "file") FetchClipBlob(v, Field(d, "name"), kind == "image");
                                    else if (kind == "empty") lock (clipToSet) clipToSet.Enqueue(new ClipSet());
                                }
                            }
                            else if (ev == "clipboard")
                            {
                                if (snapshot) { snapshot = false; if (lastClip == null) lastClip = d; }
                                else if (clipSync && d.Length > 0 && d != lastClip)
                                {
                                    lastClip = d;
                                    lock (clipToSet) clipToSet.Enqueue(new ClipSet { text = d });
                                }
                            }
                            ev = null;
                            data.Length = 0;
                            continue;
                        }
                        if (line.StartsWith("event:")) ev = line.Substring(6).Trim();
                        else if (line.StartsWith("data:"))
                        {
                            string d = line.Substring(5);
                            if (d.StartsWith(" ")) d = d.Substring(1);
                            if (data.Length > 0) data.Append('\n');
                            data.Append(d);
                        }
                    }
                }
            }
            catch { }
            Thread.Sleep(1500);
        }
    }

    // ------------------------------------------------------------------ trackpad and keyboard

    static void ControlLoop()
    {
        string file = Path.Combine(Dir, "session.txt");
        string cookie = File.Exists(file) ? File.ReadAllText(file).Trim() : null;
        bool announced = false;
        while (true)
        {
            string at = phone;
            try
            {
                if (cookie == null) { cookie = Pair(); session = cookie; TunnelLearn(); }
                session = cookie;
                at = phone;
                HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://" + at + ":" + PhonePort + "/api/control/stream");
                controlReq = r;
                r.Proxy = null;
                r.UserAgent = Ua;
                r.Timeout = 5000;
                // The phone sends a keep-alive every 3 s while its Control tab is open and every
                // 25 s otherwise (this header says we accept the slow one), so silence this long
                // means it is gone.
                r.ReadWriteTimeout = 40000;
                r.Headers["Bridge-Heartbeat"] = "slow";
                r.Headers["Cookie"] = cookie;
                HttpWebResponse resp;
                try { resp = (HttpWebResponse)r.GetResponse(); }
                catch (WebException e)
                {
                    HttpWebResponse er = e.Response as HttpWebResponse;
                    if (er != null && (int)er.StatusCode == 401)
                    {
                        er.Close();
                        Say("The phone no longer knows this laptop; asking again.");
                        cookie = null;
                        try { File.Delete(file); } catch { }
                        continue;
                    }
                    throw;
                }
                using (resp)
                using (StreamReader rd = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                {
                    if (!announced)
                    {
                        Say("Ready. The phone's Control tab now drives this laptop.");
                        relayReady.WaitOne(3000);
                        if (localPort > 0)
                        {
                            Say("Opening the Localhost 8787 page at http://localhost:" + localPort + "/ for full-speed transfers.");
                            try { Process.Start("http://localhost:" + localPort + "/"); } catch { }
                        }
                        announced = true;
                    }
                    else Say("Reconnected, " + LinkName(at) + ".");
                    string line;
                    while ((line = rd.ReadLine()) != null)
                    {
                        try { Handle(line); } catch { }
                    }
                }
                if (phone == at) Say("The phone closed the connection.");
            }
            catch (Exception e)
            {
                // Moving to another link drops this stream on purpose; it reconnects there.
                if (phone == at) Say("Lost the phone (" + e.Message + ").");
            }
            controlReq = null;
            Thread.Sleep(800);
            if (phone == null || !Ping(phone)) FindPhone(false);
        }
    }

    static void Handle(string line)
    {
        string[] a = line.Split(' ');
        switch (a[0])
        {
            case "v":
                MasterVolume.Set(float.Parse(a[1], System.Globalization.CultureInfo.InvariantCulture));
                volumeDirty = true;
                break;
            case "da":
                PointAt(float.Parse(a[1], System.Globalization.CultureInfo.InvariantCulture),
                        float.Parse(a[2], System.Globalization.CultureInfo.InvariantCulture));
                break;
            case "vm":
                MasterVolume.SetMute(!MasterVolume.Muted());
                volumeDirty = true;
                break;
            case "m": Mouse(MOVE, int.Parse(a[1]), int.Parse(a[2]), 0); break;
            case "b": Button(a[1], a[2] == "d"); break;
            case "c": Button(a[1], true); Button(a[1], false); break;
            case "w":
                int wy = int.Parse(a[1]), wx = int.Parse(a[2]);
                if (wy != 0) Mouse(WHEEL, 0, 0, wy);
                if (wx != 0) Mouse(HWHEEL, 0, 0, wx);
                break;
            case "z":
                Key("ctrl", true);
                Mouse(WHEEL, 0, 0, 120 * int.Parse(a[1]));
                Key("ctrl", false);
                break;
            case "kd": Key(a[1], true); break;
            case "ku": Key(a[1], false); break;
            case "k": Key(a[1], true); Key(a[1], false); break;
            case "h":
                string[] keys = a[1].Split('+');
                foreach (string k in keys) Key(k, true);
                for (int i = keys.Length - 1; i >= 0; i--) Key(keys[i], false);
                break;
            case "t": Type(Uri.UnescapeDataString(line.Substring(2))); break;
        }
    }

    // ------------------------------------------------------------------ the phone's screen
    //
    // "Phone screen" on the page opens the phone in a window here, to watch and use with this
    // laptop's mouse and keyboard, sound included: scrcpy (github.com/Genymobile/scrcpy), which
    // talks to the phone over adb. It is fetched from its official release the first time and
    // checked against the release's own checksum. The phone needs USB debugging on, once.

    static Process screen;

    static void OpenPhoneScreen()
    {
        try
        {
            if (screen != null && !screen.HasExited) { Say("The phone's screen is already open."); return; }
            string exe = FindScrcpy() ?? FetchScrcpy();
            if (exe == null) return;
            string adb = FindAdb(Path.GetDirectoryName(exe));
            // The cable when there is one: it is the fastest and needs nothing else.
            string usb = null;
            foreach (string line in RunOut(adb, "devices").Split('\n'))
            {
                string[] p = line.Trim().Split('\t');
                if (p.Length == 2 && p[1] == "device" && !p[0].Contains(":")) { usb = p[0]; break; }
            }
            string target;
            if (usb != null) target = "-s " + usb;
            else if (phone != null) target = "--tcpip=" + phone + ":5555";
            else { Say("Plug the phone in with a USB cable (USB debugging on), then try again."); return; }

            var psi = new ProcessStartInfo(exe,
                target + " --window-title=\"Phone (Localhost 8787)\" --stay-awake --video-bit-rate=16M --max-fps=60");
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardError = true;
            psi.RedirectStandardOutput = true;
            psi.EnvironmentVariables["ADB"] = adb;
            psi.WorkingDirectory = Path.GetDirectoryName(exe);
            Say("Opening the phone's screen" + (usb != null ? " over the USB cable." : " over Wi-Fi."));
            screen = Process.Start(psi);
            string err = screen.StandardError.ReadToEnd() + screen.StandardOutput.ReadToEnd();
            screen.WaitForExit();
            if (screen.ExitCode != 0)
            {
                if (err.Contains("Could not find any ADB device") || err.Contains("failed to connect") || err.Contains("Could not connect"))
                    Say("Could not reach the phone over adb. On the phone: Developer options, turn on USB debugging " +
                        "(and on Xiaomi, USB debugging (Security settings)); plug it in once, and allow this computer.");
                else Say("The phone's screen closed: " + LastLine(err));
            }
        }
        catch (Exception e) { Say("Could not open the phone's screen (" + e.Message + ")."); }
    }

    static string LastLine(string s)
    {
        string[] lines = s.Trim().Split('\n');
        return lines.Length == 0 ? "" : lines[lines.Length - 1].Trim();
    }

    static string RunOut(string exe, string args)
    {
        var psi = new ProcessStartInfo(exe, args);
        psi.UseShellExecute = false; psi.CreateNoWindow = true; psi.RedirectStandardOutput = true;
        using (Process p = Process.Start(psi)) { string o = p.StandardOutput.ReadToEnd(); p.WaitForExit(15000); return o; }
    }

    static string ScrcpyHome
    {
        get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BlazeIt", "scrcpy"); }
    }

    static string FindScrcpy()
    {
        foreach (string dir in new string[] { ScrcpyHome, Path.Combine(HelperDir, "scrcpy") })
        {
            string exe = Path.Combine(dir, "scrcpy.exe");
            if (File.Exists(exe)) return exe;
        }
        return null;
    }

    /** The Android SDK's adb when there is one (two different adbs fight over the same port), else scrcpy's own. */
    static string FindAdb(string scrcpyDir)
    {
        string sdk = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk", "platform-tools", "adb.exe");
        if (File.Exists(sdk)) return sdk;
        return Path.Combine(scrcpyDir, "adb.exe");
    }

    /** Downloads the latest scrcpy for 64-bit Windows from its official GitHub release, checked. */
    static string FetchScrcpy()
    {
        try
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            Say("Getting scrcpy (about 11 MB), the piece that shows the phone's screen, from its official release...");
            string api;
            var r = (HttpWebRequest)WebRequest.Create("https://api.github.com/repos/Genymobile/scrcpy/releases/latest");
            r.UserAgent = Ua;
            using (var resp = r.GetResponse()) using (var rd = new StreamReader(resp.GetResponseStream())) api = rd.ReadToEnd();
            // The 64-bit Windows zip, then its checksum and link, which follow its name in the listing.
            Match n = Regex.Match(api, "\"name\":\\s*\"(scrcpy-win64-v[^\"]+\\.zip)\"");
            string rest = n.Success ? api.Substring(n.Index, Math.Min(6000, api.Length - n.Index)) : "";
            Match d = Regex.Match(rest, "\"digest\":\\s*\"sha256:([0-9a-f]{64})\"");
            Match u = Regex.Match(rest, "\"browser_download_url\":\\s*\"([^\"]+)\"");
            if (!n.Success || !d.Success || !u.Success) { Say("Could not find scrcpy's download. Get it from github.com/Genymobile/scrcpy and unzip it into " + ScrcpyHome); return null; }
            string zip = Path.Combine(Path.GetTempPath(), n.Groups[1].Value);
            using (var wc = new WebClient()) { wc.Headers["User-Agent"] = Ua; wc.DownloadFile(u.Groups[1].Value, zip); }
            string sum;
            using (var sha = System.Security.Cryptography.SHA256.Create()) using (var fs = File.OpenRead(zip))
                sum = BitConverter.ToString(sha.ComputeHash(fs)).Replace("-", "").ToLowerInvariant();
            if (sum != d.Groups[1].Value) { File.Delete(zip); Say("scrcpy's download did not match its checksum; not using it."); return null; }
            string tmp = ScrcpyHome + ".part";
            if (Directory.Exists(tmp)) Directory.Delete(tmp, true);
            System.IO.Compression.ZipFile.ExtractToDirectory(zip, tmp);
            File.Delete(zip);
            // The zip holds one folder (scrcpy-win64-vX.Y); that folder becomes ScrcpyHome.
            string[] inner = Directory.GetDirectories(tmp);
            string src = inner.Length == 1 && !File.Exists(Path.Combine(tmp, "scrcpy.exe")) ? inner[0] : tmp;
            if (Directory.Exists(ScrcpyHome)) Directory.Delete(ScrcpyHome, true);
            Directory.Move(src, ScrcpyHome);
            if (Directory.Exists(tmp)) Directory.Delete(tmp, true);
            Say("scrcpy is ready.");
            return Path.Combine(ScrcpyHome, "scrcpy.exe");
        }
        catch (Exception e) { Say("Could not get scrcpy (" + e.Message + ")."); return null; }
    }

    // ------------------------------------------------------------------ the phone as a second screen
    //
    // The phone asks for it, listening on a port; this captures a monitor with ffmpeg (on the
    // GPU: Desktop Duplication, and NVENC when there is an NVIDIA card) and streams it there as
    // H.264. The monitor is the extra one a virtual-display driver adds, which makes the phone a
    // real second screen; with none, the laptop's own screen is mirrored. Touches on the phone
    // come back as "da x y" (where on that monitor, as fractions) and ordinary clicks.
    //
    // The virtual display is the phone's: this puts it on the desktop (extended, right of the
    // laptop's screens) when the phone asks and takes it off when the phone closes, so windows
    // left on it come back instead of sitting on a screen nobody can see. The laptop's own screen
    // stays the main one, with the taskbar and new windows. The monitor list is read from Windows
    // every time: WinForms' Screen keeps the list it saw first and, in this window-less process,
    // never hears of a change, so a display extended after the helper started was never found.

    static Process secondScreen;
    /** Bumped by every start and stop, so a stream started earlier knows it is no longer wanted. */
    static int screenGen;
    /** The monitor on the phone, and the whole desktop, in real pixels, for "da". */
    static volatile Displays.Mon shown, desk;
    /** Set when the monitors change under a running stream, so it restarts on the right one. */
    static volatile bool relayout;
    /** The virtual display this helper put on the desktop, to take off again when the phone closes. */
    static volatile string attachedHere;
    /** The same, for a helper closed mid-stream: the next one takes it off. */
    static string AttachedFile { get { return Path.Combine(Dir, "second-screen.txt"); } }
    /**
     * The virtual display whose HDR this helper turned off, to turn back on. With HDR on, Windows
     * draws ordinary white at the "SDR content brightness" level (about 2.5 times the white of an
     * 8-bit picture), so the 8-bit capture came out 2.5 times too bright with a fifth of it pure
     * white. The phone shows an ordinary picture, so the display it shows needs none.
     */
    static volatile string hdrOffHere;
    static string HdrFile { get { return Path.Combine(Dir, "second-screen-hdr.txt"); } }
    /**
     * The phone's refresh rate, and its H.264 decoder's throughput in 16x16 blocks a second
     * (2,073,600 is 4K at 60): together they decide the virtual display's mode and the frame
     * rate. Older phones send neither, and get 60 and 4K60.
     */
    static volatile int phoneHz = 60;
    static long phoneBlocks = 2073600;
    /** The virtual display's mode before this helper changed it, "device|w|h|hz", to put back. */
    static volatile string modeBefore;
    static string ModeFile { get { return Path.Combine(Dir, "second-screen-mode.txt"); } }

    static long Blocks(int w, int h) { return (long)((w + 15) / 16) * ((h + 15) / 16); }

    /** The frame rate a monitor can be sent at: its own rate, the phone's, and what the decoder takes at its size. */
    static int StreamRate(Displays.Mon m)
    {
        int rate = Math.Min(phoneHz, m.Hz > 1 ? m.Hz : 60);
        rate = (int)Math.Min(rate, phoneBlocks / Math.Max(1, Blocks(m.W, m.H)));
        return Math.Max(30, rate);
    }

    /**
     * Puts the virtual display in the mode that suits the phone: the fastest refresh rate the
     * phone shows (120 on a 120 Hz phone), then the sharpest size its decoder takes at that rate
     * (2560x1440 for a decoder rated 4K at 60), nearest the phone's shape.
     */
    static Displays.Mon FitMode(Displays.Mon t, int w, int h)
    {
        double want = h > 0 ? (double)w / h : 16.0 / 9;
        int[] best = null;
        foreach (int[] m in Displays.Modes(t.Device))
        {
            if (m[2] > phoneHz + 1 || m[0] < 1280 || m[0] < m[1] || Blocks(m[0], m[1]) * m[2] > phoneBlocks) continue;
            if (best == null || m[2] > best[2] ||
                (m[2] == best[2] && (long)m[0] * m[1] > (long)best[0] * best[1]) ||
                (m[2] == best[2] && (long)m[0] * m[1] == (long)best[0] * best[1] &&
                    Math.Abs(Math.Log((double)m[0] / m[1] / want)) < Math.Abs(Math.Log((double)best[0] / best[1] / want))))
                best = m;
        }
        if (best == null || (best[0] == t.W && best[1] == t.H && best[2] == t.Hz)) return t;
        if (modeBefore == null)
        {
            modeBefore = t.Device + "|" + t.W + "|" + t.H + "|" + t.Hz;
            try { File.WriteAllText(ModeFile, modeBefore); } catch { }
        }
        if (!Displays.SetMode(t.Device, best[0], best[1], best[2])) return t;
        Say("The phone's screen is now " + best[0] + "x" + best[1] + " at " + best[2] + " Hz, the sharpest the phone takes at its full " + best[2] + " frames a second.");
        Thread.Sleep(300);
        var now = Displays.Attached().Find(m => m.Device == t.Device);
        return now ?? t;
    }

    static string FindFfmpeg()
    {
        string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        foreach (string c in new string[] {
            Path.Combine(HelperDir, "ffmpeg", "ffmpeg.exe"),
            Path.Combine(local, "Microsoft", "WinGet", "Links", "ffmpeg.exe") })
            if (File.Exists(c)) return c;
        try
        {
            string w = RunOut("where.exe", "ffmpeg").Split('\n')[0].Trim();
            if (File.Exists(w)) return w;
            string pk = Path.Combine(local, "Microsoft", "WinGet", "Packages");
            if (Directory.Exists(pk))
                foreach (string f in Directory.GetFiles(pk, "ffmpeg.exe", SearchOption.AllDirectories)) return f;
        }
        catch { }
        return null;
    }

    /**
     * From another network (the phone reached through the tunnel): the laptop's own main screen,
     * to see and use it, made smaller and sent at what the link carries. Starts at 800 kbit/s (an
     * Airtel-to-Jio road carries 1 to 1.8 Mbit/s, often less), steps down when the picture backs
     * up on its way and back up after a calm minute, to 2 Mbit/s at most.
     */
    static volatile int remoteKbit = 700;

    static Displays.Mon MainScreen()
    {
        var mons = Displays.Attached();
        desk = Displays.Desktop(mons);
        return mons.Find(m => m.Primary) ?? (mons.Count > 0 ? mons[0] : null);
    }

    static void StartSecondScreen(string at, int port, int w, int h, bool http)
    {
        int gen = Interlocked.Increment(ref screenGen);
        // A file "screen-far.txt" beside the helper sends the far picture on any link, for trying it out.
        bool remote = http && (at == TunnelHost || File.Exists(Path.Combine(Dir, "screen-far.txt")));
        try
        {
            KillStream();
            string ff = FindFfmpeg();
            if (ff == null)
            {
                Say("The second screen needs ffmpeg on this laptop: in a terminal, run  winget install Gyan.FFmpeg  then try again.");
                return;
            }
            Displays.Mon target = remote ? MainScreen() : PrepareScreen(w, h, true);
            if (http) StartSound(ff, at, remote);
            string said = null;
            int failures = 0;
            while (target != null && gen == screenGen)
            {
                if (said != target.Device)
                {
                    said = target.Device;
                    if (remote) Say("Showing this laptop's screen on the phone, from another network: smaller, at what the link carries (" + remoteKbit + " kbit/s to start).");
                    else SayShowing(target);
                }
                string end = Capture(ff, target, at, port, gen, http, remote);
                if (gen != screenGen) return;
                // The phone closed it, or went (unplugged, out of reach) without saying so: put
                // the laptop's screens back rather than leave a display nobody sees.
                if (end == "phone") { StopSecondScreen(); return; }
                if (end == "relayout") failures = 0;
                else if (++failures >= 3) { Say("Could not stream this screen to the phone."); StopSecondScreen(); return; }
                // The monitors changed, or the capture broke on a change: look again.
                Thread.Sleep(300);
                if (gen != screenGen) return;
                target = remote ? MainScreen() : PrepareScreen(w, h, false);
            }
        }
        catch (Exception e) { Say("Second screen: " + e.Message); }
    }

    /**
     * The monitor for the phone: the virtual display (extended first when it is off or only
     * duplicating the laptop's screen, if "attach"), else any other extra monitor, else the
     * laptop's own screen to mirror. When the virtual display has become the main one, the
     * laptop's own screen is made the main one again.
     */
    static Displays.Mon PrepareScreen(int w, int h, bool attach)
    {
        var mons = Displays.Attached();
        if (attach && !mons.Exists(m => m.Virtual) && Displays.VirtualInstalled())
        {
            bool cloned = mons.Exists(m => m.Cloned);
            // As Windows+P's Extend does; failing that, the virtual display added by itself.
            if (Displays.Extend()) mons = WaitForVirtual();
            if (!mons.Exists(m => m.Virtual))
            {
                string dev = Displays.DetachedVirtual();
                if (dev != null && Displays.Attach(dev, mons, w, h)) mons = WaitForVirtual();
            }
            var added = mons.Find(m => m.Virtual);
            if (added != null)
            {
                // Put back as it was when the phone closes: duplicating again, or off.
                attachedHere = added.Device + (cloned ? "|clone" : "");
                try { File.WriteAllText(AttachedFile, attachedHere); } catch { }
                if (cloned) Say("Windows was duplicating the laptop's screen onto the virtual display; it is extended now, so the phone is a screen of its own.");
            }
            else Say("Could not extend the desktop onto the virtual display. In Windows' display settings choose \"Extend these displays\", then try again.");
        }
        var virt = mons.Find(m => m.Virtual);
        var own = mons.Find(m => !m.Virtual);
        if (virt != null && virt.Primary && own != null && Displays.MakePrimary(own.Device, mons))
        {
            Say("The phone's screen had become the main display, with the taskbar and new windows on it. The laptop's screen is the main one again.");
            Thread.Sleep(300);
            mons = Displays.Attached();
        }
        var target = Pick(mons);
        if (target != null && target.Virtual)
        {
            target = FitMode(target, w, h);
            mons = Displays.Attached();
        }
        desk = Displays.Desktop(mons);
        if (target != null && target.Virtual && hdrOffHere == null && Displays.HdrOn(target.Device))
        {
            if (Displays.SetHdr(target.Device, false))
            {
                hdrOffHere = target.Device;
                try { File.WriteAllText(HdrFile, target.Device); } catch { }
                Say("HDR is off on the phone's screen while the phone shows it, so its colours come out right; it goes back on after.");
            }
            else Say("The virtual display has HDR on, which makes the phone's picture too bright; turn \"Use HDR\" off for it in Windows' display settings.");
        }
        return target;
    }

    /** Windows takes a moment to bring a display up. */
    static List<Displays.Mon> WaitForVirtual()
    {
        var mons = Displays.Attached();
        for (int i = 0; i < 20 && !mons.Exists(m => m.Virtual); i++) { Thread.Sleep(150); mons = Displays.Attached(); }
        return mons;
    }

    static Displays.Mon Pick(List<Displays.Mon> mons)
    {
        return mons.Find(m => m.Virtual) ?? mons.Find(m => !m.Primary) ?? mons.Find(m => m.Primary) ?? (mons.Count > 0 ? mons[0] : null);
    }

    static void SayShowing(Displays.Mon m)
    {
        string size = " (" + m.W + "x" + m.H + ", " + StreamRate(m) + " frames a second)";
        if (m.Virtual) Say("The phone is a second screen" + size + ", extended from the laptop's. Drag windows onto it as onto any screen.");
        else if (!m.Primary) Say("Showing monitor " + m.Device.Replace("\\\\.\\", "") + size + " on the phone.");
        else Say("No extra monitor on this laptop, so the phone mirrors this screen. Install a virtual display driver to make it a real second screen.");
    }

    /**
     * How a monitor in HDR has to be captured, as the brightness Windows gives SDR white there
     * (see SdrWhiteNits); 0 when the ordinary 8-bit capture is right. Windows draws the desktop
     * of an HDR monitor at that brightness and hands an 8-bit capture everything above 80 nits
     * clipped to white: at the slider's lowest (80) that loses only HDR highlights, above it
     * the whole picture comes out too bright. (The virtual display has its HDR turned off.)
     */
    static double HdrWhite(Displays.Mon target)
    {
        if (target.Virtual || !Displays.HdrOn(target.Device)) return 0;
        double white = Displays.SdrWhiteNits(target.Device);
        return white > 85 ? white : 0;
    }

    /**
     * The shader that turns a 16-bit capture of an HDR desktop back into the SDR picture: the
     * capture is linear light, 1.0 = 80 nits, with SDR content at (sRGB-decoded) x white/80.
     * Dividing by that and encoding sRGB again gives the picture Windows started from, within
     * 2 of 255; brighter HDR parts roll off towards white instead of clipping.
     */
    static string HdrShader(double white)
    {
        string gain = (80.0 / white).ToString("0.0#####", System.Globalization.CultureInfo.InvariantCulture);
        return
            "//!HOOK MAIN\n//!BIND HOOKED\n//!DESC Localhost 8787: HDR desktop to SDR\n" +
            "vec4 hook() {\n" +
            "    vec3 l = max(HOOKED_texOff(0).rgb * " + gain + ", 0.0);\n" +
            "    vec3 k = 0.9 + 0.1 * (1.0 - exp(-(l - 0.9) / 0.1));\n" +
            "    l = mix(l, k, step(0.9, l));\n" +
            "    vec3 s = mix(12.92 * l, 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, l));\n" +
            "    return vec4(s, 1.0);\n" +
            "}\n";
    }

    /** The ffmpeg command lines that stream a monitor to "dest", best first; the shader goes in Dir. */
    static List<string> CaptureTries(Displays.Mon target, double hdrWhite, string dest)
    {
        int adapter, output; string adapterName;
        if (!Dxgi.Find(target.Device, out adapter, out output, out adapterName)) { adapter = -1; output = -1; adapterName = ""; }

        // NVENC and ffmpeg's own converter both turn the screen into video with the BT.601 sums.
        // Labelled in full (on the frames: this ffmpeg takes the label from them, not from the
        // encoder's options), the phone decodes with the same sums; half-labelled, as NVENC
        // leaves it, the phone assumed BT.709 for a picture this size and the colours shifted.
        string label = "setparams=color_primaries=bt470bg:color_trc=smpte170m";
        // As many frames as the monitor, the phone's panel and its decoder all manage (120 on a
        // 120 Hz phone), with the bit rate going up with them: 40 Mbit/s at 60, 80 at 120.
        int fps = StreamRate(target);
        int mbit = Math.Min(80, Math.Max(40, 40 * fps / 60));
        string enc = " -c:v h264_nvenc -preset p1 -tune ull -zerolatency 1 -rc cbr -b:v " + mbit + "M -maxrate " + mbit + "M -bufsize " + Math.Max(3, mbit / 13) + "M -g " + (fps * 2) + " -bf 0";
        // Frames captured on the NVIDIA card go straight to its encoder; from any other
        // adapter they are copied across first.
        bool nvidia = adapterName.IndexOf("NVIDIA", StringComparison.OrdinalIgnoreCase) >= 0;
        string grab = "ddagrab=output_idx=" + output + ":framerate=" + fps + ":draw_mouse=1";
        var tries = new List<string>();
        if (adapter >= 0 && hdrWhite > 0)
        {
            // An HDR monitor: the 16-bit capture, turned back into SDR on the GPU (libplacebo,
            // Vulkan) by HdrShader. About 50 frames a second at 2560x1600: the 16-bit frames go
            // through memory, as ffmpeg cannot hand them from Direct3D to Vulkan here.
            try { File.WriteAllText(Path.Combine(Dir, "hdr-to-sdr.hook"), HdrShader(hdrWhite)); } catch { }
            tries.Add("-hide_banner -loglevel error -init_hw_device d3d11va=cap:" + adapter + " -init_hw_device vulkan=vk -filter_hw_device cap" +
                " -filter_complex \"" + grab + ":output_fmt=16bit,hwdownload,format=rgbaf16le," +
                "setparams=color_primaries=bt709:color_trc=iec61966-2-1:colorspace=gbr:range=pc," +
                "libplacebo=format=nv12:colorspace=bt470bg:color_primaries=bt709:color_trc=iec61966-2-1:range=tv:peak_detect=0:custom_shader_path=hdr-to-sdr.hook," +
                label + "[v]\" -map \"[v]\"" + enc + dest);
        }
        if (adapter >= 0)
            tries.Add("-hide_banner -loglevel error -init_hw_device d3d11va=cap:" + adapter + " -filter_hw_device cap" +
                " -filter_complex \"" + grab + (nvidia ? "" : ",hwdownload,format=bgra") + "," + label + "[v]\" -map \"[v]\"" + enc + dest);
        // Plain GDI capture of that part of the desktop reaches any monitor.
        string gdi = "-hide_banner -loglevel error -f gdigrab -framerate " + Math.Min(fps, 60) + " -offset_x " + target.X + " -offset_y " + target.Y +
            " -video_size " + target.W + "x" + target.H + " -draw_mouse 1 -i desktop";
        tries.Add(gdi + " -vf " + label + enc + dest);
        tries.Add(gdi + " -vf format=yuv420p," + label + ":colorspace=bt470bg:range=tv -c:v libx264 -preset ultrafast -tune zerolatency -b:v 20M -g 120 -bf 0" + dest);
        return tries;
    }

    /**
     * The command lines for the main screen from afar, best first: made smaller (1280 wide, down to
     * 854 on a slower link), 24 frames a second (15 when slower), at "kbit" with little buffering so
     * the picture is current; on the NVIDIA card when there is one.
     */
    static List<string> RemoteTries(Displays.Mon target, int kbit)
    {
        int adapter, output; string adapterName;
        if (!Dxgi.Find(target.Device, out adapter, out output, out adapterName)) { adapter = -1; output = -1; adapterName = ""; }
        int wide = kbit >= 1200 ? 1280 : kbit >= 500 ? 1024 : 854;
        int fps = kbit >= 500 ? 20 : 15;
        string label = "setparams=color_primaries=bt470bg:color_trc=smpte170m";
        string scale = "scale=" + wide + ":-2:flags=bilinear,format=yuv420p";
        // No large picture every few seconds: the refresh is spread over the frames (intra refresh),
        // so the stream stays as even as the link, and a lost moment heals within a second.
        // A whole refresh every 2 s, so a frame dropped on a slow moment heals within that; each frame
        // starts with a delimiter, so the sender can tell frames apart and drop late ones (PostFrames).
        string rate = " -b:v " + kbit + "k -maxrate " + kbit + "k -bufsize " + Math.Max(100, kbit / 2) + "k -g " + (fps * 2) + " -bf 0";
        string nv = " -c:v h264_nvenc -preset p4 -tune ll -zerolatency 1 -rc cbr -intra-refresh 1" + rate;
        string x264 = " -c:v libx264 -preset veryfast -tune zerolatency -intra-refresh 1" + rate;
        bool nvidia = adapterName.IndexOf("NVIDIA", StringComparison.OrdinalIgnoreCase) >= 0;
        string dest = " -bsf:v h264_metadata=aud=insert -f h264 pipe:1";
        var tries = new List<string>();
        if (adapter >= 0)
        {
            string grab = "-hide_banner -loglevel error -init_hw_device d3d11va=cap:" + adapter + " -filter_hw_device cap -filter_complex \"ddagrab=output_idx=" + output +
                ":framerate=" + fps + ":draw_mouse=1,hwdownload,format=bgra," + scale + "," + label + "[v]\" -map \"[v]\"";
            if (nvidia) tries.Add(grab + nv + dest);
            tries.Add(grab + x264 + dest);
        }
        tries.Add("-hide_banner -loglevel error -f gdigrab -framerate " + fps + " -offset_x " + target.X + " -offset_y " + target.Y +
            " -video_size " + target.W + "x" + target.H + " -draw_mouse 1 -i desktop -vf " + scale + "," + label + ":colorspace=bt470bg:range=tv" + x264 + dest);
        return tries;
    }

    /**
     * Posts ffmpeg's stream to the phone's page port, the way this helper reaches the phone now
     * (the cable, Wi-Fi, the tunnel). From afar it watches what is still waiting to go: more than
     * two seconds of picture means the link is behind, so it asks for a smaller, slower stream;
     * after 45 s with nothing waiting, a sharper one. Returns "rate" (start again at the new
     * rate), "phone" (the phone closed it, or could not be reached) or "ended".
     */
    static string PostStream(Process p, string at, bool remote)
    {
        var queue = new Queue<byte[]>();
        long queued = 0;
        bool eof = false;
        object gate = new object();
        var rd = new Thread(delegate ()
        {
            try
            {
                Stream src = p.StandardOutput.BaseStream;
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = src.Read(buf, 0, buf.Length)) > 0)
                {
                    byte[] c = new byte[n];
                    Buffer.BlockCopy(buf, 0, c, 0, n);
                    lock (gate) { queue.Enqueue(c); queued += n; System.Threading.Monitor.Pulse(gate); }
                }
            }
            catch { }
            lock (gate) { eof = true; System.Threading.Monitor.Pulse(gate); }
        });
        rd.IsBackground = true; rd.Start();
        string why = "ended";
        try
        {
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create("http://" + at + ":" + PhonePort + "/api/display/stream");
            req.Proxy = null;
            req.Method = "POST";
            req.UserAgent = Ua;
            req.Headers["Cookie"] = session;
            req.ContentType = "video/h264";
            req.SendChunked = true;
            req.AllowWriteStreamBuffering = false;
            // .NET's Timeout runs until the phone answers, which it does only when the stream ends, so
            // none; a write that waits half a minute means the link is gone.
            req.Timeout = System.Threading.Timeout.Infinite;
            req.ReadWriteTimeout = 30000;
            req.ServicePoint.Expect100Continue = false;
            using (Stream o = req.GetRequestStream())
            {
                long behind = (long)remoteKbit * 1000 / 8 * 2;
                DateTime calm = DateTime.UtcNow;
                while (true)
                {
                    byte[] c;
                    lock (gate)
                    {
                        while (queue.Count == 0 && !eof) System.Threading.Monitor.Wait(gate, 1000);
                        if (queue.Count == 0) break;
                        c = queue.Dequeue();
                        queued -= c.Length;
                    }
                    o.Write(c, 0, c.Length);
                    o.Flush();
                    if (!remote) continue;
                    long q;
                    lock (gate) q = queued;
                    if (q > behind && remoteKbit > 250)
                    {
                        remoteKbit = Math.Max(250, remoteKbit * 6 / 10);
                        Say("The link to the phone is slower: the laptop's screen goes at " + remoteKbit + " kbit/s now.");
                        why = "rate";
                        break;
                    }
                    if (q > behind / 8) calm = DateTime.UtcNow;
                    else if ((DateTime.UtcNow - calm).TotalSeconds > 60 && remoteKbit < 2000)
                    {
                        remoteKbit = Math.Min(2000, remoteKbit * 13 / 10);
                        Say("The link to the phone keeps up: the laptop's screen goes at " + remoteKbit + " kbit/s now.");
                        why = "rate";
                        break;
                    }
                }
            }
            if (why == "ended") using (req.GetResponse()) { }
        }
        catch (Exception) { if (why == "ended") why = "phone"; }
        try { if (!p.HasExited) p.Kill(); } catch { }
        return why;
    }

    /** The phone's page-port upload for the screen, as PostStream opens it. */
    static HttpWebRequest StreamRequest(string at)
    {
        HttpWebRequest req = (HttpWebRequest)WebRequest.Create("http://" + at + ":" + PhonePort + "/api/display/stream");
        req.Proxy = null;
        req.Method = "POST";
        req.UserAgent = Ua;
        req.Headers["Cookie"] = session;
        req.ContentType = "video/h264";
        req.SendChunked = true;
        req.AllowWriteStreamBuffering = false;
        req.Timeout = System.Threading.Timeout.Infinite;
        req.ReadWriteTimeout = 30000;
        req.ServicePoint.Expect100Continue = false;
        return req;
    }

    /** Whether an access unit starts the picture afresh (SPS or IDR), which is never dropped. */
    static bool StartsAfresh(byte[] f)
    {
        for (int i = 0; i + 3 < f.Length; i++)
            if (f[i] == 0 && f[i + 1] == 0 && f[i + 2] == 1)
            {
                int t = f[i + 3] & 0x1F;
                if (t == 7 || t == 5) return true;
            }
        return false;
    }

    /**
     * From afar: ffmpeg's stream cut into frames at its delimiters and posted to the phone, newest
     * first in mind. A frame still waiting after half a second is dropped (all but the newest),
     * as a video call does, so the phone shows the laptop as it is now rather than falling
     * behind; the refresh spread over the frames heals the gap within 2 s. Many drops in ten
     * seconds ask for a smaller stream; a minute with none, a sharper one. Returns as PostStream.
     */
    static string PostFrames(Process p, string at)
    {
        var frames = new LinkedList<KeyValuePair<DateTime, byte[]>>();
        bool eof = false;
        object gate = new object();
        var rd = new Thread(delegate ()
        {
            try
            {
                Stream src = p.StandardOutput.BaseStream;
                MemoryStream acc = new MemoryStream();
                byte[] buf = new byte[64 * 1024];
                int n, scanFrom = 1;
                while ((n = src.Read(buf, 0, buf.Length)) > 0)
                {
                    acc.Write(buf, 0, n);
                    byte[] a = acc.GetBuffer();
                    int len = (int)acc.Length, start = 0;
                    for (int i = Math.Max(1, scanFrom); i + 3 < len; i++)
                    {
                        if (a[i] != 0 || a[i + 1] != 0 || a[i + 2] != 1 || (a[i + 3] & 0x1F) != 9) continue;
                        int cut = a[i - 1] == 0 ? i - 1 : i;
                        if (cut > start)
                        {
                            byte[] f = new byte[cut - start];
                            Buffer.BlockCopy(a, start, f, 0, f.Length);
                            lock (gate) { frames.AddLast(new KeyValuePair<DateTime, byte[]>(DateTime.UtcNow, f)); System.Threading.Monitor.Pulse(gate); }
                        }
                        start = cut;
                        i = cut + 4;
                    }
                    MemoryStream rest = new MemoryStream();
                    rest.Write(a, start, len - start);
                    acc = rest;
                    scanFrom = Math.Max(1, (int)acc.Length - 4);
                }
                byte[] tail = acc.ToArray();
                if (tail.Length > 0) lock (gate) frames.AddLast(new KeyValuePair<DateTime, byte[]>(DateTime.UtcNow, tail));
            }
            catch { }
            lock (gate) { eof = true; System.Threading.Monitor.Pulse(gate); }
        });
        rd.IsBackground = true; rd.Start();
        string why = "ended";
        try
        {
            HttpWebRequest req = StreamRequest(at);
            using (Stream o = req.GetRequestStream())
            {
                int dropped = 0, calm = 0;
                DateTime window = DateTime.UtcNow;
                while (true)
                {
                    byte[] f;
                    lock (gate)
                    {
                        while (frames.Count == 0 && !eof) System.Threading.Monitor.Wait(gate, 1000);
                        if (frames.Count == 0) break;
                        while (frames.Count > 1 && (DateTime.UtcNow - frames.First.Value.Key).TotalMilliseconds > 500 && !StartsAfresh(frames.First.Value.Value))
                        {
                            frames.RemoveFirst();
                            dropped++;
                        }
                        f = frames.First.Value.Value;
                        frames.RemoveFirst();
                    }
                    o.Write(f, 0, f.Length);
                    o.Flush();
                    if ((DateTime.UtcNow - window).TotalSeconds < 10) continue;
                    window = DateTime.UtcNow;
                    if (dropped > 20 && remoteKbit > 250)
                    {
                        remoteKbit = Math.Max(250, remoteKbit * 6 / 10);
                        Say("The link to the phone is slower: the laptop's screen goes at " + remoteKbit + " kbit/s now.");
                        why = "rate";
                        break;
                    }
                    calm = dropped == 0 ? calm + 10 : 0;
                    dropped = 0;
                    if (calm >= 60 && remoteKbit < 2000)
                    {
                        remoteKbit = Math.Min(2000, remoteKbit * 13 / 10);
                        Say("The link to the phone keeps up: the laptop's screen goes at " + remoteKbit + " kbit/s now.");
                        why = "rate";
                        break;
                    }
                }
            }
            if (why == "ended") using (req.GetResponse()) { }
        }
        catch (Exception) { if (why == "ended") why = "phone"; }
        try { if (!p.HasExited) p.Kill(); } catch { }
        return why;
    }

    /** One go at streaming a monitor: "phone" (the phone closed it), "relayout", "ended", "failed" or "stopped". */
    static string Capture(string ff, Displays.Mon target, string at, int port, int gen, bool http, bool remote)
    {
        shown = target;
        double hdrWhite = remote ? 0 : HdrWhite(target);
        if (hdrWhite > 0) Say("This screen is in HDR, with ordinary white at " + Math.Round(hdrWhite) + " nits; the phone gets it turned back into an ordinary picture, so it is not too bright.");
        string dest = http ? " -f h264 pipe:1" : " -f h264 \"tcp://" + at + ":" + port + "?tcp_nodelay=1\"";
        foreach (string args in remote ? RemoteTries(target, remoteKbit) : CaptureTries(target, hdrWhite, dest))
        {
            relayout = false;
            var psi = new ProcessStartInfo(ff, args);
            psi.UseShellExecute = false; psi.CreateNoWindow = true; psi.RedirectStandardError = true;
            psi.RedirectStandardOutput = http;
            psi.WorkingDirectory = Dir; // where the HDR shader is
            var p = Process.Start(psi);
            secondScreen = p;
            if (gen != screenGen) { KillStream(); return "stopped"; } // stopped while this one started
            string err = "";
            var reader = new Thread(delegate () { try { err = p.StandardError.ReadToEnd(); } catch { } });
            reader.IsBackground = true; reader.Start();
            var watch = new Thread(delegate () { WatchScreens(gen, target, hdrWhite, p, remote); });
            watch.IsBackground = true; watch.Start();
            string sent = null;
            Thread pump = null;
            if (http)
            {
                pump = new Thread(delegate () { sent = remote ? PostFrames(p, at) : PostStream(p, at, false); });
                pump.IsBackground = true; pump.Start();
            }
            // Still going after a few seconds: it works. Stopped at once: try the next way.
            bool quick = p.WaitForExit(4000);
            if (!quick) p.WaitForExit();
            reader.Join(500);
            if (pump != null) pump.Join(5000);
            if (gen != screenGen || secondScreen != p) return "stopped";
            if (relayout) return "relayout";
            if (sent == "rate") return "relayout";   // the link asked for another size: start again at it
            if (sent == "phone") return "phone";
            if (err.Contains("Connection refused") || err.Contains("Connection reset") || err.Contains("Broken pipe")) return "phone";
            if (!quick) return "ended";
        }
        return "failed";
    }

    /**
     * While a stream runs: if the monitors change (the display extended, moved, resized, made the
     * main one, or taken off), or HDR or its SDR brightness is changed on the monitor shown, end
     * it so it starts again on the right monitor, at its new place, captured the right way.
     */
    static void WatchScreens(int gen, Displays.Mon target, double hdrWhite, Process p, bool remote)
    {
        while (gen == screenGen && !p.HasExited)
        {
            Thread.Sleep(1500);
            try
            {
                var mons = Displays.Attached();
                if (mons.Count == 0) continue;
                desk = Displays.Desktop(mons);
                var now = mons.Find(m => m.Device == target.Device);
                var best = Pick(mons);
                bool moved = now == null || now.X != target.X || now.Y != target.Y || now.W != target.W || now.H != target.H;
                // From afar it is always the main screen: another monitor is no better.
                bool better = !remote && best != null && best.Device != target.Device;
                bool main = !remote && now != null && now.Virtual && now.Primary;
                bool light = !moved && Math.Abs(HdrWhite(now) - hdrWhite) > 1;
                if (moved || better || main || light)
                {
                    relayout = true;
                    try { p.Kill(); } catch { }
                    return;
                }
            }
            catch { }
        }
    }

    static void KillStream()
    {
        Process p = secondScreen;
        secondScreen = null;
        try { if (p != null && !p.HasExited) p.Kill(); } catch { }
    }

    static void StopSecondScreen()
    {
        Interlocked.Increment(ref screenGen);
        KillStream();
        StopSound();
        ReleaseScreen();
    }

    // ------------------------------------------------------------------ the laptop's sound with its screen
    //
    // What the speakers play goes to the phone with the screen: WASAPI loopback (Loopback),
    // AAC from ffmpeg (160 kbit/s nearby, 64 from afar), posted to the page's port like the
    // picture. From afar, sound still waiting after 400 ms is dropped, so it keeps up with the
    // picture rather than falling behind it.

    static Process soundProc;
    static int soundGen;

    static void StopSound()
    {
        Interlocked.Increment(ref soundGen);
        Process p = soundProc;
        soundProc = null;
        try { if (p != null && !p.HasExited) p.Kill(); } catch { }
    }

    static void StartSound(string ff, string at, bool remote)
    {
        StopSound();
        int gen = soundGen;
        var t = new Thread(delegate ()
        {
            Loopback cap = null;
            try
            {
                cap = new Loopback();
                if (!cap.Float) { Say("The laptop's sound is not in a format this helper takes, so the phone gets the picture only."); return; }
                string args = "-hide_banner -loglevel error -f f32le -ar " + cap.Rate + " -ac " + cap.Channels + " -i pipe:0 -ac 2 -ar 48000 -c:a aac -b:a " +
                    (remote ? "64k" : "160k") + " -f adts pipe:1";
                var psi = new ProcessStartInfo(ff, args);
                psi.UseShellExecute = false; psi.CreateNoWindow = true;
                psi.RedirectStandardInput = true; psi.RedirectStandardOutput = true; psi.RedirectStandardError = true;
                var p = Process.Start(psi);
                soundProc = p;
                var errs = new Thread(delegate () { try { p.StandardError.ReadToEnd(); } catch { } });
                errs.IsBackground = true; errs.Start();
                // Captured sound into ffmpeg every 10 ms; silence keeps flowing as zeros once something has played.
                var feed = new Thread(delegate ()
                {
                    try
                    {
                        Stream o = p.StandardInput.BaseStream;
                        while (gen == soundGen && !p.HasExited)
                        {
                            byte[] b = cap.Read();
                            if (b.Length > 0) { o.Write(b, 0, b.Length); o.Flush(); }
                            Thread.Sleep(10);
                        }
                    }
                    catch { }
                });
                feed.IsBackground = true; feed.Start();
                PostSound(p, at, remote, gen);
            }
            catch (Exception e) { Say("The laptop's sound could not be captured (" + e.Message + "); the phone gets the picture only."); }
            finally { if (cap != null) cap.Dispose(); }
        });
        t.IsBackground = true; t.Start();
    }

    /** ffmpeg's ADTS frames to the phone; from afar, frames older than 400 ms are let go. */
    static void PostSound(Process p, string at, bool remote, int gen)
    {
        var frames = new LinkedList<KeyValuePair<DateTime, byte[]>>();
        bool eof = false;
        object gate = new object();
        var rd = new Thread(delegate ()
        {
            try
            {
                Stream src = p.StandardOutput.BaseStream;
                byte[] head = new byte[7];
                while (true)
                {
                    if (!ReadFull(src, head, 0, 7)) break;
                    if (head[0] != 0xFF || (head[1] & 0xF0) != 0xF0) { continue; }
                    int len = ((head[3] & 3) << 11) | (head[4] << 3) | (head[5] >> 5);
                    if (len < 7) continue;
                    byte[] f = new byte[len];
                    Buffer.BlockCopy(head, 0, f, 0, 7);
                    if (!ReadFull(src, f, 7, len - 7)) break;
                    lock (gate) { frames.AddLast(new KeyValuePair<DateTime, byte[]>(DateTime.UtcNow, f)); System.Threading.Monitor.Pulse(gate); }
                }
            }
            catch { }
            lock (gate) { eof = true; System.Threading.Monitor.Pulse(gate); }
        });
        rd.IsBackground = true; rd.Start();
        try
        {
            // Set up as the picture's upload is (StreamRequest), to the sound's own route.
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create("http://" + at + ":" + PhonePort + "/api/display/audio");
            req.Proxy = null; req.Method = "POST"; req.UserAgent = Ua; req.Headers["Cookie"] = session;
            req.ContentType = "audio/aac"; req.SendChunked = true; req.AllowWriteStreamBuffering = false;
            req.Timeout = System.Threading.Timeout.Infinite; req.ReadWriteTimeout = 30000;
            req.ServicePoint.Expect100Continue = false;
            using (Stream o = req.GetRequestStream())
            {
                while (gen == soundGen)
                {
                    byte[] f;
                    lock (gate)
                    {
                        while (frames.Count == 0 && !eof) System.Threading.Monitor.Wait(gate, 1000);
                        if (frames.Count == 0) break;
                        if (remote)
                            while (frames.Count > 1 && (DateTime.UtcNow - frames.First.Value.Key).TotalMilliseconds > 400) frames.RemoveFirst();
                        f = frames.First.Value.Value;
                        frames.RemoveFirst();
                    }
                    o.Write(f, 0, f.Length);
                    o.Flush();
                }
            }
        }
        catch { }
        try { if (!p.HasExited) p.Kill(); } catch { }
    }

    static bool ReadFull(Stream s, byte[] b, int off, int n)
    {
        while (n > 0)
        {
            int r = s.Read(b, off, n);
            if (r <= 0) return false;
            off += r; n -= r;
        }
        return true;
    }

    /**
     * Puts the virtual display this helper extended back as it found it: duplicating the laptop's
     * screen again, or off the desktop. Either way Windows moves its windows to the laptop's screen.
     */
    static void ReleaseScreen()
    {
        lock (releaseLock)
        {
            // Each part is forgotten (and its file deleted) only once it is really put back:
            // Windows refuses display changes while it is locked or showing the screen saver,
            // and then RetryRelease tries again until it can.
            // HDR and its mode first: once the display is off the desktop there is nothing to set.
            // HDR before the mode, as a request made while the mode is still changing gets lost.
            string hdr = hdrOffHere;
            if (!string.IsNullOrEmpty(hdr) && (Displays.SetHdr(hdr, true) || !OnDesktop(hdr))) hdr = null;
            hdrOffHere = hdr;
            if (hdr == null) try { File.Delete(HdrFile); } catch { }

            string mode = modeBefore;
            if (!string.IsNullOrEmpty(mode))
            {
                string[] m = mode.Split('|');
                if (m.Length != 4 || !OnDesktop(m[0]) || Displays.SetMode(m[0], int.Parse(m[1]), int.Parse(m[2]), int.Parse(m[3]))) mode = null;
            }
            modeBefore = mode;
            if (mode == null) try { File.Delete(ModeFile); } catch { }

            string was = attachedHere;
            if (!string.IsNullOrEmpty(was) && hdr == null && mode == null)
            {
                string[] p = was.Split('|');
                bool clone = p.Length > 1 && p[1] == "clone";
                if (clone ? Displays.Duplicate() : (Displays.Detach(p[0]) || !OnDesktop(p[0])))
                {
                    Say(clone
                        ? "The phone's screen is closed; Windows duplicates the laptop's screen again, as before, and the windows on it are back on the laptop's."
                        : "Took the phone's screen off the desktop; its windows are back on the laptop's.");
                    was = null;
                }
            }
            attachedHere = was;
            if (was == null) try { File.Delete(AttachedFile); } catch { }

            if (hdrOffHere != null || modeBefore != null || attachedHere != null) RetryRelease();
        }
    }

    static readonly object releaseLock = new object();
    static volatile bool retrying;

    static bool OnDesktop(string dev) { return Displays.Attached().Exists(m => m.Device == dev); }

    /** Tries ReleaseScreen again every 15 seconds until Windows lets it, unless the phone takes the screen again. */
    static void RetryRelease()
    {
        if (retrying) return;
        retrying = true;
        Say("Windows is not taking display changes right now (locked, or the screen saver is on); the laptop's screens go back as they were as soon as it does.");
        var t = new Thread(delegate ()
        {
            try
            {
                while (hdrOffHere != null || modeBefore != null || attachedHere != null)
                {
                    Thread.Sleep(15000);
                    if (secondScreen != null) break; // in use again: it is released when that ends
                    ReleaseScreen(); // "retrying" stays set, so a failure here starts no second loop
                }
            }
            finally { retrying = false; }
        });
        t.IsBackground = true;
        t.Start();
    }

    /** The mouse to a point on the captured monitor, given as fractions of its width and height. */
    static void PointAt(float fx, float fy)
    {
        Displays.Mon s = shown, d = desk;
        if (s == null || d == null) return;
        // Everything here is in real pixels, so the input goes out as from a DPI-aware program:
        // scaled coordinates differ per monitor once the two screens have different scaling.
        double x = s.X + fx * (s.W - 1), y = s.Y + fy * (s.H - 1);
        INPUT i = new INPUT();
        i.type = 0;
        i.u.mi.dx = (int)Math.Round((x - d.X) * 65535.0 / Math.Max(1, d.W - 1));
        i.u.mi.dy = (int)Math.Round((y - d.Y) * 65535.0 / Math.Max(1, d.H - 1));
        i.u.mi.dwFlags = MOVE | ABSOLUTE | VIRTUALDESK;
        IntPtr was = Displays.RealPixels();
        Send(i);
        Displays.RestoreDpi(was);
    }

    // ------------------------------------------------------------------ volume
    //
    // The phone's volume bar sets the laptop's master volume directly, and shows where it is:
    // this reports the level on connecting, right after each change, and whenever it is
    // changed on the laptop itself (keys, the taskbar).

    static volatile bool volumeDirty = true;

    static void VolumeLoop()
    {
        string last = null;
        int n = 0;
        while (true)
        {
            Thread.Sleep(250);
            try
            {
                string cookie = session;
                if (cookie == null || phone == null) { last = null; continue; }
                // Quick after a change from the phone; otherwise a look every two seconds.
                if (!volumeDirty && ++n < 8) continue;
                n = 0; volumeDirty = false;
                string now = "{\"level\":" + MasterVolume.Get().ToString("0.###", System.Globalization.CultureInfo.InvariantCulture) +
                    ",\"muted\":" + (MasterVolume.Muted() ? "true" : "false") + "}";
                if (now == last) continue;
                HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://" + phone + ":" + PhonePort + "/api/control/volume");
                r.Method = "POST"; r.Proxy = null; r.UserAgent = Ua; r.Timeout = 3000; r.KeepAlive = false;
                r.ContentType = "application/json"; r.Headers["Cookie"] = cookie;
                byte[] body = Encoding.UTF8.GetBytes(now);
                r.ContentLength = body.Length;
                using (Stream o = r.GetRequestStream()) o.Write(body, 0, body.Length);
                using (WebResponse resp = r.GetResponse()) { }
                last = now;
            }
            catch { last = null; }
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx; public int dy; public int mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }

    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public InputUnion u; }

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint count, INPUT[] inputs, int size);

    const uint MOVE = 0x0001, LEFTDOWN = 0x0002, LEFTUP = 0x0004, RIGHTDOWN = 0x0008, RIGHTUP = 0x0010,
        MIDDLEDOWN = 0x0020, MIDDLEUP = 0x0040, WHEEL = 0x0800, HWHEEL = 0x1000, ABSOLUTE = 0x8000, VIRTUALDESK = 0x4000;
    const uint KEY_EXTENDED = 0x0001, KEY_UP = 0x0002, KEY_UNICODE = 0x0004;

    static void Send(INPUT i)
    {
        SendInput(1, new INPUT[] { i }, Marshal.SizeOf(typeof(INPUT)));
    }

    static void Mouse(uint flags, int dx, int dy, int data)
    {
        INPUT i = new INPUT();
        i.type = 0;
        i.u.mi.dx = dx;
        i.u.mi.dy = dy;
        i.u.mi.mouseData = data;
        i.u.mi.dwFlags = flags;
        Send(i);
    }

    static void Button(string which, bool down)
    {
        uint f = which == "r" ? (down ? RIGHTDOWN : RIGHTUP)
            : which == "m" ? (down ? MIDDLEDOWN : MIDDLEUP)
            : (down ? LEFTDOWN : LEFTUP);
        Mouse(f, 0, 0, 0);
    }

    static readonly Dictionary<string, ushort> Vk = new Dictionary<string, ushort>
    {
        { "enter", 0x0D }, { "back", 0x08 }, { "tab", 0x09 }, { "esc", 0x1B }, { "space", 0x20 },
        { "left", 0x25 }, { "up", 0x26 }, { "right", 0x27 }, { "down", 0x28 },
        { "del", 0x2E }, { "insert", 0x2D }, { "home", 0x24 }, { "end", 0x23 }, { "pgup", 0x21 }, { "pgdn", 0x22 },
        { "win", 0x5B }, { "ctrl", 0x11 }, { "alt", 0x12 }, { "shift", 0x10 },
        { "f1", 0x70 }, { "f2", 0x71 }, { "f3", 0x72 }, { "f4", 0x73 }, { "f5", 0x74 }, { "f6", 0x75 },
        { "f7", 0x76 }, { "f8", 0x77 }, { "f9", 0x78 }, { "f10", 0x79 }, { "f11", 0x7A }, { "f12", 0x7B },
        // Media keys from the Control tab: they reach whatever is playing, even in the background.
        { "playpause", 0xB3 }, { "next", 0xB0 }, { "prev", 0xB1 },
        { "volup", 0xAF }, { "voldown", 0xAE }, { "mute", 0xAD },
    };

    static readonly HashSet<string> Extended = new HashSet<string>
    {
        "left", "up", "right", "down", "del", "insert", "home", "end", "pgup", "pgdn", "win",
    };

    static void Key(string name, bool down)
    {
        ushort vk;
        if (!Vk.TryGetValue(name, out vk))
        {
            // Single letters and digits, for shortcuts such as Ctrl+C.
            if (name.Length != 1 || !char.IsLetterOrDigit(name[0])) return;
            vk = (ushort)char.ToUpperInvariant(name[0]);
        }
        INPUT i = new INPUT();
        i.type = 1;
        i.u.ki.wVk = vk;
        i.u.ki.dwFlags = (down ? 0 : KEY_UP) | (Extended.Contains(name) ? KEY_EXTENDED : 0);
        Send(i);
    }

    static void Type(string text)
    {
        foreach (char ch in text)
        {
            INPUT i = new INPUT();
            i.type = 1;
            i.u.ki.wScan = ch;
            i.u.ki.dwFlags = KEY_UNICODE;
            Send(i);
            i.u.ki.dwFlags = KEY_UNICODE | KEY_UP;
            Send(i);
        }
    }

    // ------------------------------------------------------------------ localhost relay

    [DllImport("kernel32.dll")] static extern bool SetHandleInformation(IntPtr handle, int mask, int flags);

    /**
     * Stops programs this helper starts (scrcpy, ffmpeg, and the adb server scrcpy starts, which
     * lives on) from inheriting this socket. Without it, a replaced helper's page port stays held
     * by them, and the next helper cannot serve the page on it.
     */
    static void KeepToSelf(Socket s)
    {
        try { SetHandleInformation(s.Handle, 1, 0); } catch { } // HANDLE_FLAG_INHERIT off
    }

    static void RelayLoop()
    {
        TcpListener l = null;
        foreach (int port in new int[] { 8787, 8797, 8807 })
        {
            try
            {
                l = new TcpListener(IPAddress.Loopback, port);
                l.Start();
                KeepToSelf(l.Server);
                localPort = port;
                break;
            }
            catch (SocketException) { l = null; }
        }
        if (l == null)
        {
            localPort = 0;
            Say("Could not serve the page on this laptop (ports 8787, 8797 and 8807 are all busy). The trackpad still works.");
        }
        relayReady.Set();
        if (l == null) return;
        Thread sweep = new Thread(SweepLoop);
        sweep.IsBackground = true;
        sweep.Start();
        AcceptLoop(l);
    }

    static void AcceptLoop(TcpListener l)
    {
        while (true)
        {
            TcpClient c;
            // A connection that fails before it is taken is the browser's business, not the end of the page.
            try { c = l.AcceptTcpClient(); }
            catch (Exception e) { Log("Taking a connection: " + e.Message); Thread.Sleep(200); continue; }
            KeepToSelf(c.Client);
            Thread t = new Thread(delegate ()
            {
                try { Bridge(c); }
                catch { try { c.Close(); } catch { } }
            });
            t.IsBackground = true;
            t.Start();
        }
    }

    /** One browser connection relayed to the phone: which address it went to, and when bytes last moved. */
    class Relayed
    {
        public TcpClient browser, toPhone;
        public string host;
        public volatile int last = Environment.TickCount;
    }

    static readonly List<Relayed> relayed = new List<Relayed>();

    /**
     * The browser keeps its connections to localhost open and reuses them, and each one stays
     * tied to the link it was opened over. After a move to the cable, downloads would carry on
     * over the old Wi-Fi. So once such a connection goes quiet (between requests, or because
     * the old link is gone) it is closed, and the browser opens a new one over the new link.
     * A transfer still running over the old link is left to finish.
     */
    static void SweepLoop()
    {
        while (true)
        {
            Thread.Sleep(1000);
            string now = phone;
            Relayed[] all;
            lock (relayed) all = relayed.ToArray();
            foreach (Relayed r in all)
            {
                if (r.host == now || unchecked(Environment.TickCount - r.last) < 1500) continue;
                try { r.browser.Close(); } catch { }
                try { r.toPhone.Close(); } catch { }
            }
        }
    }

    static void Bridge(TcpClient browser)
    {
        Relayed r = new Relayed();
        r.browser = browser;
        r.toPhone = new TcpClient();
        r.host = phone;
        lock (relayed) relayed.Add(r);
        try
        {
            browser.NoDelay = true;
            r.toPhone.NoDelay = true;
            browser.ReceiveBufferSize = browser.SendBufferSize = 4 << 20;
            r.toPhone.ReceiveBufferSize = r.toPhone.SendBufferSize = 4 << 20;
            r.toPhone.Connect(r.host, PhonePort);
            KeepToSelf(r.toPhone.Client);
            NetworkStream a = browser.GetStream(), b = r.toPhone.GetStream();
            Thread up = new Thread(delegate () { Pump(a, b, r.toPhone.Client, r); });
            up.IsBackground = true;
            up.Start();
            Pump(b, a, browser.Client, r);
            up.Join();
        }
        catch { }
        finally
        {
            lock (relayed) relayed.Remove(r);
            browser.Close();
            r.toPhone.Close();
        }
    }

    static void Pump(NetworkStream from, NetworkStream to, Socket toSocket, Relayed r)
    {
        byte[] buf = new byte[1 << 20];
        try
        {
            int n;
            while ((n = from.Read(buf, 0, buf.Length)) > 0)
            {
                r.last = Environment.TickCount;
                to.Write(buf, 0, n);
            }
        }
        catch { }
        try { toSocket.Shutdown(SocketShutdown.Send); } catch { }
    }

    // ------------------------------------------------------------------ the tunnel
    //
    // From another network: one TCP connection to the phone's global IPv6, our own encryption,
    // every connection a stream inside it (docs/tunnel-protocol.md). Served here at 127.0.0.3:8787,
    // the way USB debugging is at 127.0.0.2, so everything else works unchanged.

    const string TunnelHost = "127.0.0.3";
    static volatile Tunnel87.Conn tunnel;
    static bool tunnelServing;
    static readonly object tunnelLock = new object();
    static readonly string TunnelFile = Path.Combine(Dir, "tunnel.json");
    static readonly HashSet<string> tunnelSaid = new HashSet<string>();

    /** While the phone is reachable: its tunnel keys and addresses, kept for when it is not. */
    static void TunnelLearn()
    {
        try
        {
            using (HttpWebResponse r = Http("GET", "/api/tunnel", session, 5000))
            {
                string b = Body(r);
                if ((int)r.StatusCode == 200 && b.Contains("\"key\"")) File.WriteAllText(TunnelFile, b);
            }
        }
        catch { }
    }

    /** The tunnel's keys are from before the current session (an older pairing): out of date. */
    static bool TunnelStale()
    {
        try
        {
            string s = Path.Combine(Dir, "session.txt");
            return File.Exists(s) && (!File.Exists(TunnelFile) || File.GetLastWriteTimeUtc(TunnelFile) < File.GetLastWriteTimeUtc(s));
        }
        catch { return false; }
    }

    static string TunnelConf() { try { return File.Exists(TunnelFile) ? File.ReadAllText(TunnelFile) : null; } catch { return null; } }

    static List<string> TunnelAddrs(string conf)
    {
        List<string> list = new List<string>();
        Match m = Regex.Match(conf ?? "", "\"addrs\"\\s*:\\s*\\[([^\\]]*)\\]");
        if (m.Success) foreach (Match a in Regex.Matches(m.Groups[1].Value, "\"([^\"]+)\"")) list.Add(a.Groups[1].Value);
        return list;
    }

    /** New addresses from the phone (over the tunnel, or typed from its Home), first one first. */
    static void TunnelSaveAddrs(List<string> addrs)
    {
        string conf = TunnelConf();
        if (conf == null || addrs == null || addrs.Count == 0) return;
        string arr = "[" + string.Join(",", addrs.ConvertAll<string>(delegate (string a) { return "\"" + a + "\""; }).ToArray()) + "]";
        string now = Regex.Replace(conf, "\"addrs\"\\s*:\\s*\\[[^\\]]*\\]", "\"addrs\":" + arr);
        if (now != conf) try { File.WriteAllText(TunnelFile, now); } catch { }
    }

    /** The last way in, from another network. The tunnel's address here, or null when it cannot. */
    static string TunnelPath()
    {
        string conf = TunnelConf();
        if (conf == null) return null;
        lock (tunnelLock)
        {
            Tunnel87.Conn t = tunnel;
            if (t != null && t.Alive) return TunnelHost;
            if (!tunnelServing)
            {
                try
                {
                    TcpListener l = new TcpListener(IPAddress.Parse(TunnelHost), PhonePort);
                    l.Start();
                    KeepToSelf(l.Server);
                    tunnelServing = true;
                    Thread acc = new Thread(delegate ()
                    {
                        while (true)
                        {
                            TcpClient c;
                            try { c = l.AcceptTcpClient(); }
                            catch (Exception e) { Log("Taking a connection for the tunnel: " + e.Message); Thread.Sleep(200); continue; }
                            KeepToSelf(c.Client);
                            Tunnel87.Conn now = tunnel;
                            if (now == null || !now.Alive) { c.Close(); continue; }
                            try { now.OpenStream(c, PhonePort); } catch { c.Close(); }
                        }
                    });
                    acc.IsBackground = true;
                    acc.Start();
                }
                catch (Exception e) { Log("Serving the tunnel: " + e.Message); return null; }
            }
            string id = Regex.Match(conf, "\"id\"\\s*:\\s*\"([0-9a-f]+)\"").Groups[1].Value;
            string key = Regex.Match(conf, "\"key\"\\s*:\\s*\"([^\"]+)\"").Groups[1].Value;
            Match pm = Regex.Match(conf, "\"port\"\\s*:\\s*(\\d+)");
            int port = pm.Success ? int.Parse(pm.Groups[1].Value) : 8789;
            if (id.Length != 32 || key.Length == 0) return null;
            byte[] tid = new byte[16];
            for (int i = 0; i < 16; i++) tid[i] = Convert.ToByte(id.Substring(i * 2, 2), 16);
            byte[] psk = Convert.FromBase64String(key);
            // Its saved addresses all at once; meanwhile the board is asked where it is now, in case
            // its IPv6 changed while the two were apart, and any new ones join the race the moment they come.
            List<string> saved = TunnelAddrs(conf);
            Action<string, Exception> failed = delegate (string host, Exception e)
            {
                string why = "Could not reach the phone at " + host + " over the internet (" + e.Message + ").";
                lock (tunnelSaid) if (tunnelSaid.Add(why)) Say(why);
            };
            Tunnel87.Race race = new Tunnel87.Race(port, tid, psk, Environment.MachineName, TunnelSaveAddrs, Log, failed);
            foreach (string a in saved) race.Add(a);
            Thread asking = new Thread(delegate ()
            {
                List<string> now = Tunnel87.Where(psk), fresh = new List<string>();
                foreach (string a in now) if (!saved.Contains(a)) fresh.Add(a);
                if (fresh.Count > 0)
                {
                    Log("The phone's addresses changed: " + string.Join(", ", fresh.ToArray()));
                    TunnelSaveAddrs(now);
                    foreach (string a in fresh) race.Add(a);
                }
                race.Close();
            });
            asking.IsBackground = true;
            asking.Start();
            tunnel = race.Wait(20000);
            if (tunnel != null) return TunnelHost;
            // No IPv6 way to it: across IPv4, punched through both NATs.
            try
            {
                tunnel = Tunnel87.DialPunched(tid, psk, Environment.MachineName, TunnelSaveAddrs, Log);
                Log("Tunnel to the phone over UDP, punched through");
                return TunnelHost;
            }
            catch (Exception e)
            {
                string why = "Could not reach the phone across IPv4 (" + e.Message + ").";
                lock (tunnelSaid) if (tunnelSaid.Add(why)) Say(why);
            }
        }
        return null;
    }

    /** Keeps the tunnel's details current, and leaves it the moment a local path answers. */
    static void TunnelLoop()
    {
        int lastLearn = Environment.TickCount - 700000;
        while (true)
        {
            Thread.Sleep(5000);
            try
            {
                if (phone == null || session == null) continue;
                if (phone != TunnelHost)
                {
                    // Every 10 minutes while close; at once when the keys are older than the session (a new
                    // pairing gives this laptop new keys, and the old ones stop working the moment it does).
                    if (unchecked(Environment.TickCount - lastLearn) > 600000 || (TunnelStale() && unchecked(Environment.TickCount - lastLearn) > 30000)) { TunnelLearn(); lastLearn = Environment.TickCount; }
                    Tunnel87.Conn t = tunnel;
                    bool used;
                    lock (relayed) used = relayed.Exists(delegate (Relayed r) { return r.host == TunnelHost; });
                    if (t != null && t.Alive && !used) t.Close("not needed any more");
                    continue;
                }
                foreach (string c in Candidates())
                {
                    if (c != TunnelHost && Ping(c))
                    {
                        Say("The phone is close again: " + c + ", " + LinkName(c) + ".");
                        MoveTo(c);
                        lastLearn = Environment.TickCount - 700000;
                        break;
                    }
                }
            }
            catch (Exception e) { Log("Tunnel loop: " + e.Message); }
        }
    }
}

/**
 * The L87 tunnel's client end (docs/tunnel-protocol.md), the same as the Linux and Mac helper's:
 * X25519, HKDF-SHA256, SHAKE256 keystream and HMAC-SHA256 tags; streams with flow control.
 */
public static class Tunnel87
{
    const int HELLO = 1, OPEN = 2, DATA = 3, FIN = 4, RST = 5, CREDIT = 6, PING = 7, PONG = 8, ADDR = 9, BYE = 10;
    const int WINDOW = 512 * 1024, CHUNK = 16384, CREDIT_STEP = 128 * 1024;

    // ---------------------------------------------------------------- X25519 (RFC 7748)

    static readonly System.Numerics.BigInteger P = System.Numerics.BigInteger.Pow(2, 255) - 19;

    static System.Numerics.BigInteger Mod(System.Numerics.BigInteger v)
    {
        v = System.Numerics.BigInteger.Remainder(v, P);
        return v.Sign < 0 ? v + P : v;
    }

    static System.Numerics.BigInteger Le(byte[] b)
    {
        byte[] t = new byte[b.Length + 1];
        Array.Copy(b, t, b.Length);
        return new System.Numerics.BigInteger(t);
    }

    /** Plain big integers, not constant-time: every key here is used once. */
    public static byte[] X25519(byte[] scalar, byte[] u)
    {
        byte[] k = (byte[])scalar.Clone();
        k[0] &= 248; k[31] &= 127; k[31] |= 64;
        System.Numerics.BigInteger kk = Le(k);
        byte[] uu = (byte[])u.Clone();
        uu[31] &= 127;
        System.Numerics.BigInteger x1 = Mod(Le(uu)), x2 = 1, z2 = 0, x3 = x1, z3 = 1, tmp;
        int swap = 0;
        for (int t = 254; t >= 0; t--)
        {
            int bit = (int)((kk >> t) & 1);
            if ((swap ^ bit) == 1) { tmp = x2; x2 = x3; x3 = tmp; tmp = z2; z2 = z3; z3 = tmp; }
            swap = bit;
            System.Numerics.BigInteger a = x2 + z2, b = x2 - z2, c = x3 + z3, d = x3 - z3;
            System.Numerics.BigInteger aa = Mod(a * a), bb = Mod(b * b), e = aa - bb;
            System.Numerics.BigInteger da = Mod(d * a), cb = Mod(c * b);
            System.Numerics.BigInteger s = da + cb, m = da - cb;
            x3 = Mod(s * s);
            z3 = Mod(x1 * Mod(m * m));
            x2 = Mod(aa * bb);
            z2 = Mod(e * (aa + 121665 * e));
        }
        if (swap == 1) { x2 = x3; z2 = z3; }
        byte[] outp = Mod(x2 * System.Numerics.BigInteger.ModPow(z2, P - 2, P)).ToByteArray();
        byte[] r = new byte[32];
        Array.Copy(outp, r, Math.Min(32, outp.Length));
        return r;
    }

    // ---------------------------------------------------------------- HMAC, HKDF

    public static byte[] Hmac(byte[] key, params byte[][] parts)
    {
        using (System.Security.Cryptography.HMACSHA256 h = new System.Security.Cryptography.HMACSHA256(key))
        {
            foreach (byte[] p in parts) h.TransformBlock(p, 0, p.Length, null, 0);
            h.TransformFinalBlock(new byte[0], 0, 0);
            return h.Hash;
        }
    }

    public static byte[] Hmac16(byte[] key, params byte[][] parts)
    {
        byte[] full = Hmac(key, parts), r = new byte[16];
        Array.Copy(full, r, 16);
        return r;
    }

    static byte[] HkdfExpand(byte[] prk, byte[] info, int n)
    {
        MemoryStream o = new MemoryStream();
        byte[] t = new byte[0];
        for (int i = 1; o.Length < n; i++)
        {
            t = Hmac(prk, t, info, new byte[] { (byte)i });
            o.Write(t, 0, t.Length);
        }
        byte[] r = new byte[n];
        Array.Copy(o.ToArray(), r, n);
        return r;
    }

    static byte[] Cat(params byte[][] parts)
    {
        MemoryStream o = new MemoryStream();
        foreach (byte[] p in parts) o.Write(p, 0, p.Length);
        return o.ToArray();
    }

    static bool Same(byte[] a, byte[] b)
    {
        if (a.Length != b.Length) return false;
        int d = 0;
        for (int i = 0; i < a.Length; i++) d |= a[i] ^ b[i];
        return d == 0;
    }

    // ---------------------------------------------------------------- SHAKE256

    static readonly ulong[] RC = {
        0x0000000000000001UL, 0x0000000000008082UL, 0x800000000000808AUL, 0x8000000080008000UL,
        0x000000000000808BUL, 0x0000000080000001UL, 0x8000000080008081UL, 0x8000000000008009UL,
        0x000000000000008AUL, 0x0000000000000088UL, 0x0000000080008009UL, 0x000000008000000AUL,
        0x000000008000808BUL, 0x800000000000008BUL, 0x8000000000008089UL, 0x8000000000008003UL,
        0x8000000000008002UL, 0x8000000000000080UL, 0x000000000000800AUL, 0x800000008000000AUL,
        0x8000000080008081UL, 0x8000000000008080UL, 0x0000000080000001UL, 0x8000000080008008UL };
    static readonly int[] ROT = { 0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14 };

    static ulong Rol(ulong v, int n) { return n == 0 ? v : (v << n) | (v >> (64 - n)); }

    static void KeccakF(ulong[] a)
    {
        ulong[] c = new ulong[5], b = new ulong[25];
        for (int round = 0; round < 24; round++)
        {
            for (int x = 0; x < 5; x++) c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            for (int x = 0; x < 5; x++)
            {
                ulong d = c[(x + 4) % 5] ^ Rol(c[(x + 1) % 5], 1);
                for (int y = 0; y < 25; y += 5) a[y + x] ^= d;
            }
            for (int x = 0; x < 5; x++)
                for (int y = 0; y < 5; y++)
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = Rol(a[x + 5 * y], ROT[x + 5 * y]);
            for (int y = 0; y < 25; y += 5)
                for (int x = 0; x < 5; x++)
                    a[y + x] = b[y + x] ^ (~b[y + (x + 1) % 5] & b[y + (x + 2) % 5]);
            a[0] ^= RC[round];
        }
    }

    /** XORs SHAKE256(input) into buf[off, off+len). The input is shorter than one block here. */
    public static void Shake256Xor(byte[] input, byte[] buf, int off, int len)
    {
        const int RATE = 136;
        ulong[] s = new ulong[25];
        byte[] block = new byte[RATE];
        Array.Copy(input, block, input.Length);
        block[input.Length] ^= 0x1F;
        block[RATE - 1] ^= 0x80;
        for (int i = 0; i < RATE / 8; i++) s[i] ^= BitConverter.ToUInt64(block, i * 8);
        KeccakF(s);
        int done = 0;
        while (true)
        {
            for (int i = 0; i < RATE && done < len; i++, done++)
                buf[off + done] ^= (byte)(s[i >> 3] >> ((i & 7) * 8));
            if (done >= len) return;
            KeccakF(s);
        }
    }

    // ---------------------------------------------------------------- frames

    internal class Cipher
    {
        readonly byte[] seed;
        readonly System.Security.Cryptography.HMACSHA256 mac;
        long n;

        public Cipher(byte[] enc, byte[] macKey)
        {
            seed = new byte[enc.Length + 8];
            Array.Copy(enc, seed, enc.Length);
            mac = new System.Security.Cryptography.HMACSHA256(macKey);
        }

        byte[] Counter() { byte[] c = BitConverter.GetBytes(n); Array.Reverse(c); return c; }

        static byte[] Be32(int v) { byte[] b = BitConverter.GetBytes(v); Array.Reverse(b); return b; }

        byte[] Tag(byte[] c, byte[] len, byte[] buf, int off, int count)
        {
            mac.Initialize();
            mac.TransformBlock(c, 0, 8, null, 0);
            mac.TransformBlock(len, 0, 4, null, 0);
            mac.TransformFinalBlock(buf, off, count);
            byte[] t = new byte[16];
            Array.Copy(mac.Hash, t, 16);
            return t;
        }

        void Keystream(byte[] buf, int off, int len)
        {
            Array.Copy(Counter(), 0, seed, seed.Length - 8, 8);
            Shake256Xor(seed, buf, off, len);
            n++;
        }

        public byte[] Seal(byte[] plain)
        {
            byte[] o = new byte[4 + plain.Length + 16];
            byte[] len = Be32(plain.Length);
            Array.Copy(len, o, 4);
            Array.Copy(plain, 0, o, 4, plain.Length);
            byte[] c = Counter();
            Keystream(o, 4, plain.Length);
            Array.Copy(Tag(c, len, o, 4, plain.Length), 0, o, 4 + plain.Length, 16);
            return o;
        }

        public bool Open(byte[] ct, byte[] tag)
        {
            if (!Same(Tag(Counter(), Be32(ct.Length), ct, 0, ct.Length), tag)) return false;
            Keystream(ct, 0, ct.Length);
            return true;
        }
    }

    static byte[] ReadExact(Stream s, int n)
    {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n)
        {
            int r = s.Read(b, got, n - got);
            if (r <= 0) throw new IOException("the connection closed");
            got += r;
        }
        return b;
    }

    static int Be(byte[] b, int off) { return (b[off] << 24) | (b[off + 1] << 16) | (b[off + 2] << 8) | b[off + 3]; }

    /** Connects to the phone over TCP and runs the handshake; the connection is running when this returns. */
    public static Conn Dial(string host, int port, byte[] tid, byte[] psk, string name, Action<List<string>> onAddrs, Action<string> log)
    {
        return Dial(host, port, tid, psk, name, onAddrs, log, 8000);
    }

    public static Conn Dial(string host, int port, byte[] tid, byte[] psk, string name, Action<List<string>> onAddrs, Action<string> log, int timeoutMs)
    {
        TcpClient tcp = new TcpClient(AddressFamily.InterNetworkV6);
        tcp.Client.DualMode = true;
        try
        {
            IAsyncResult ar = tcp.BeginConnect(IPAddress.Parse(host), port, null, null);
            if (!ar.AsyncWaitHandle.WaitOne(timeoutMs)) throw new IOException("no answer in " + (timeoutMs / 1000) + " s");
            tcp.EndConnect(ar);
            tcp.NoDelay = true;
            tcp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
            return Handshake(tcp.GetStream(), tcp.Close, tid, psk, name, onAddrs, log);
        }
        catch
        {
            try { tcp.Close(); } catch { }
            throw;
        }
    }

    /** Addresses dialled at once as they come; the first tunnel up wins, any later ones are closed. */
    public class Race
    {
        readonly int port;
        readonly byte[] tid, psk;
        readonly string name;
        readonly Action<List<string>> onAddrs;
        readonly Action<string> log;
        readonly Action<string, Exception> failed;
        readonly object gate = new object();
        Conn won;
        int pending;
        bool open = true;

        public Race(int port, byte[] tid, byte[] psk, string name, Action<List<string>> onAddrs, Action<string> log, Action<string, Exception> failed)
        {
            this.port = port; this.tid = tid; this.psk = psk; this.name = name; this.onAddrs = onAddrs; this.log = log; this.failed = failed;
        }

        public void Add(string host)
        {
            lock (gate) { if (won != null) return; pending++; }
            Thread t = new Thread(delegate ()
            {
                Conn c = null;
                try { c = Dial(host, port, tid, psk, name, onAddrs, log, 4000); }
                catch (Exception e) { if (failed != null) failed(host, e); }
                lock (gate)
                {
                    pending--;
                    if (c != null && won == null) { won = c; c = null; if (log != null) log("Tunnel to [" + host + "]:" + port); }
                    Monitor.PulseAll(gate);
                }
                if (c != null) c.Close("another address answered first");
            });
            t.IsBackground = true;
            t.Start();
        }

        /** No more addresses will come. */
        public void Close() { lock (gate) { open = false; Monitor.PulseAll(gate); } }

        /** The winner, or null once every address has failed and no more will come. */
        public Conn Wait(int limitMs)
        {
            int end = Environment.TickCount + limitMs;
            lock (gate)
                while (won == null && (open || pending > 0) && unchecked(Environment.TickCount - end) < 0) Monitor.Wait(gate, 500);
            return won;
        }
    }

    /** The handshake over ns (TCP, or a path punched over UDP), then the connection, running. */
    static Conn Handshake(Stream ns, Action close, byte[] tid, byte[] psk, string name, Action<List<string>> onAddrs, Action<string> log)
    {
        try
        {
            ns.ReadTimeout = 8000;
            byte[] priv = new byte[32];
            using (System.Security.Cryptography.RandomNumberGenerator rng = System.Security.Cryptography.RandomNumberGenerator.Create()) rng.GetBytes(priv);
            byte[] nine = new byte[32]; nine[0] = 9;
            byte[] hello = Cat(Encoding.ASCII.GetBytes("L87T"), new byte[] { 1 }, tid, X25519(priv, nine));
            hello = Cat(hello, Hmac16(psk, Encoding.ASCII.GetBytes("L87T/1 hello"), hello));
            ns.Write(hello, 0, hello.Length);
            byte[] resp = ReadExact(ns, 48);
            byte[] eS = new byte[32], mac2 = new byte[16];
            Array.Copy(resp, eS, 32);
            Array.Copy(resp, 32, mac2, 0, 16);
            byte[] dh = X25519(priv, eS);
            if (Same(dh, new byte[32])) throw new IOException("a bad key from the phone");
            byte[] th;
            using (System.Security.Cryptography.SHA256 sha = System.Security.Cryptography.SHA256.Create()) th = sha.ComputeHash(Cat(hello, eS));
            byte[] okm = HkdfExpand(Hmac(psk, dh), Cat(Encoding.ASCII.GetBytes("L87T/1 keys"), th), 160);
            byte[][] k = new byte[5][];
            for (int i = 0; i < 5; i++) { k[i] = new byte[32]; Array.Copy(okm, i * 32, k[i], 0, 32); }
            if (!Same(mac2, Hmac16(k[4], Encoding.ASCII.GetBytes("L87T/1 accept"), th)))
                throw new IOException("the phone did not prove it knows this laptop");
            ns.ReadTimeout = Timeout.Infinite;
            Conn c = new Conn(close, ns, new Cipher(k[0], k[1]), new Cipher(k[2], k[3]), onAddrs, log);
            c.Start();
            c.Send(HELLO, 0, Encoding.UTF8.GetBytes("{\"name\":\"" + name.Replace("\\", "").Replace("\"", "") + "\",\"v\":1}"));
            if (!c.hello.WaitOne(8000)) { c.Close("no hello"); throw new IOException("the phone did not answer the hello"); }
            return c;
        }
        catch
        {
            try { close(); } catch { }
            throw;
        }
    }

    // ---------------------------------------------------------------- across IPv4
    //
    // When the phone's IPv6 cannot be reached (this network has none): both ends swap their public
    // IPv4 addresses as two sealed notes on a public message board, punch through their NATs over
    // UDP, and run the tunnel over a reliable stream on that path (docs/tunnel-protocol.md, "Across
    // IPv4"). The same as the phone's Punch.kt and the Linux and Mac helper's.

    public const string Board = "https://ntfy.sh";
    const int PROBE = 1, PROBE_ACK = 2, PDATA = 3, PACK = 4, PKEEP = 5, PCLOSE = 6;
    const int PHEADER = 20, PTAG = 8, MSS = 1200, PQUEUE = 4096, PWINDOW = 4096;

    static byte[] Ascii(string s) { return Encoding.ASCII.GetBytes(s); }

    static byte[] Sub(byte[] b, int off, int n) { byte[] r = new byte[n]; Array.Copy(b, off, r, 0, n); return r; }

    static void PutBe(byte[] b, int off, int v) { b[off] = (byte)(v >> 24); b[off + 1] = (byte)(v >> 16); b[off + 2] = (byte)(v >> 8); b[off + 3] = (byte)v; }

    static void Rand(byte[] b) { using (System.Security.Cryptography.RandomNumberGenerator r = System.Security.Cryptography.RandomNumberGenerator.Create()) r.GetBytes(b); }

    static string Topic(byte[] psk, string label)
    {
        byte[] h = Hmac(psk, Ascii(label));
        StringBuilder sb = new StringBuilder("l87-");
        for (int i = 0; i < 10; i++) sb.Append(h[i].ToString("x2"));
        return sb.ToString();
    }

    static string Seal(byte[] psk, string json)
    {
        byte[] nonce = new byte[16];
        Rand(nonce);
        byte[] ct = Encoding.UTF8.GetBytes(json);
        Shake256Xor(Cat(Hmac(psk, Ascii("L87P/1 seal")), nonce), ct, 0, ct.Length);
        byte[] tag = Hmac16(Hmac(psk, Ascii("L87P/1 seal mac")), nonce, ct);
        return Convert.ToBase64String(Cat(nonce, ct, tag)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }

    static string Unseal(byte[] psk, string text)
    {
        try
        {
            string t = text.Trim().Replace('-', '+').Replace('_', '/');
            t += new string('=', (4 - t.Length % 4) % 4);
            byte[] all = Convert.FromBase64String(t);
            if (all.Length < 33) return null;
            byte[] nonce = Sub(all, 0, 16), ct = Sub(all, 16, all.Length - 32), tag = Sub(all, all.Length - 16, 16);
            if (!Same(tag, Hmac16(Hmac(psk, Ascii("L87P/1 seal mac")), nonce, ct))) return null;
            Shake256Xor(Cat(Hmac(psk, Ascii("L87P/1 seal")), nonce), ct, 0, ct.Length);
            return Encoding.UTF8.GetString(ct);
        }
        catch { return null; }
    }

    /** A UDP socket on any port, which does not fail its next receive when a knock is refused (ICMP). */
    static Socket NewUdp()
    {
        Socket s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        s.Bind(new IPEndPoint(IPAddress.Any, 0));
        try { s.IOControl(-1744830452, new byte[] { 0, 0, 0, 0 }, null); } catch { }   // SIO_UDP_CONNRESET off
        return s;
    }

    /** One STUN Binding request (RFC 5389) from s: the address the server saw it come from, or null. */
    static IPEndPoint Stun(Socket s, string host, int port)
    {
        IPAddress server = null;
        try { foreach (IPAddress a in Dns.GetHostAddresses(host)) if (a.AddressFamily == AddressFamily.InterNetwork) { server = a; break; } }
        catch { }
        if (server == null) return null;
        byte[] tid = new byte[12];
        Rand(tid);
        byte[] req = Cat(new byte[] { 0, 1, 0, 0, 0x21, 0x12, 0xA4, 0x42 }, tid);
        byte[] buf = new byte[2048];
        int end = Environment.TickCount + 1500, sent = Environment.TickCount - 1000;
        s.ReceiveTimeout = 400;
        try
        {
            while (unchecked(Environment.TickCount - end) < 0)
            {
                if (unchecked(Environment.TickCount - sent) > 400) { s.SendTo(req, new IPEndPoint(server, port)); sent = Environment.TickCount; }
                EndPoint from = new IPEndPoint(IPAddress.Any, 0);
                int n;
                try { n = s.ReceiveFrom(buf, ref from); } catch (SocketException) { continue; }
                if (n < 20) continue;
                bool mine = true;
                for (int i = 0; i < 12; i++) if (buf[8 + i] != tid[i]) mine = false;
                if (!mine) continue;
                int p = 20;
                while (p + 4 <= n)
                {
                    int type = (buf[p] << 8) | buf[p + 1], len = (buf[p + 2] << 8) | buf[p + 3], v = p + 4;
                    if ((type == 0x20 || type == 1) && len >= 8 && buf[v + 1] == 1)
                    {
                        int mp = (buf[v + 2] << 8) | buf[v + 3];
                        byte[] ip = Sub(buf, v + 4, 4);
                        if (type == 0x20) { mp ^= 0x2112; ip[0] ^= 0x21; ip[1] ^= 0x12; ip[2] ^= 0xA4; ip[3] ^= 0x42; }
                        return new IPEndPoint(new IPAddress(ip), mp);
                    }
                    p = v + len + ((4 - len % 4) % 4);
                }
            }
        }
        finally { s.ReceiveTimeout = 0; }
        return null;
    }

    /** Packet tags: HMAC-SHA256 over the packet, the first 8 bytes. One key per direction. */
    internal class Tagger
    {
        readonly System.Security.Cryptography.HMACSHA256 h;
        public Tagger(byte[] key) { h = new System.Security.Cryptography.HMACSHA256(key); }

        public byte[] Packet(int kind, int role, int seq, int ack, long sack, int wnd, byte[] payload, int off, int len)
        {
            byte[] b = new byte[PHEADER + len + PTAG];
            b[0] = (byte)kind; b[1] = (byte)role;
            PutBe(b, 2, seq); PutBe(b, 6, ack); PutBe(b, 10, (int)(sack >> 32)); PutBe(b, 14, (int)sack);
            b[18] = (byte)(wnd >> 8); b[19] = (byte)wnd;
            Array.Copy(payload, off, b, PHEADER, len);
            byte[] t;
            lock (h) t = h.ComputeHash(b, 0, PHEADER + len);
            Array.Copy(t, 0, b, PHEADER + len, PTAG);
            return b;
        }

        public bool Valid(byte[] b, int n)
        {
            if (n < PHEADER + PTAG) return false;
            byte[] t;
            lock (h) t = h.ComputeHash(b, 0, n - PTAG);
            int d = 0;
            for (int i = 0; i < PTAG; i++) d |= t[i] ^ b[n - PTAG + i];
            return d == 0;
        }
    }

    internal class PathFound { public Socket s; public IPEndPoint to; }

    /**
     * Knocks from main (and 255 more sockets when this side's NAT is hard) at the other side's
     * addresses, spraying random ports on its address when its NAT is hard and this one's is not,
     * until a packet from it arrives: that socket and that address are the path. Null after 15 s.
     */
    static PathFound Knock(Socket main, bool hardHere, List<IPEndPoint> theirs, bool hardThere, byte[] tx, byte[] rx, int role)
    {
        List<Socket> socks = new List<Socket>();
        socks.Add(main);
        if (hardHere && !hardThere) for (int i = 1; i < 256; i++) { try { socks.Add(NewUdp()); } catch { break; } }
        Tagger seal = new Tagger(tx), check = new Tagger(rx);
        byte[] none = new byte[0];
        byte[] probe = seal.Packet(PROBE, role, 0, 0, 0, 0, none, 0, 0), ack = seal.Packet(PROBE_ACK, role, 0, 0, 0, 0, none, 0, 0);
        int[] ports = null;
        if (hardThere && !hardHere && theirs.Count > 0)
        {
            // The ports near the one STUN saw first (NATs that count up), then the rest at random.
            int seen = theirs[0].Port;
            List<int> order = new List<int>();
            HashSet<int> near = new HashSet<int>();
            for (int d = 1; d <= 256; d++) foreach (int p in new int[] { seen + d, seen - d }) if (p >= 1024 && p <= 65535 && near.Add(p)) order.Add(p);
            List<int> rest = new List<int>();
            for (int p = 1024; p <= 65535; p++) if (p != seen && !near.Contains(p)) rest.Add(p);
            Random rnd = new Random();
            for (int i = rest.Count - 1; i > 0; i--) { int j = rnd.Next(i + 1); int t = rest[i]; rest[i] = rest[j]; rest[j] = t; }
            order.AddRange(rest);
            ports = order.ToArray();
        }
        int start = Environment.TickCount, last = start - 1000, next = 0;
        byte[] buf = new byte[2048];
        PathFound got = null;
        while (got == null && unchecked(Environment.TickCount - start) < 15000)
        {
            if (unchecked(Environment.TickCount - last) >= 200)
            {
                last = Environment.TickCount;
                foreach (Socket s in socks) foreach (IPEndPoint t in theirs) { try { s.SendTo(probe, t); } catch { } }
            }
            if (ports != null)
                for (int i = 0; i < 30; i++, next++) { try { main.SendTo(probe, new IPEndPoint(theirs[0].Address, ports[next % ports.Length])); } catch { } }
            List<Socket> ready = new List<Socket>(socks);
            try { Socket.Select(ready, null, null, ports != null ? 100000 : 50000); } catch { Thread.Sleep(50); continue; }
            foreach (Socket s in ready)
            {
                EndPoint from = new IPEndPoint(IPAddress.Any, 0);
                int n;
                try { n = s.ReceiveFrom(buf, ref from); } catch { continue; }
                if (!check.Valid(buf, n)) continue;
                if (buf[0] == PROBE) for (int k = 0; k < 3; k++) { try { s.SendTo(ack, from); } catch { } }
                if (buf[0] == PROBE || buf[0] == PROBE_ACK) { got = new PathFound(); got.s = s; got.to = (IPEndPoint)from; break; }
            }
        }
        foreach (Socket s in socks) if (got == null || s != got.s) { try { s.Close(); } catch { } }
        return got;
    }

    static IPEndPoint ParseAddr(string a)
    {
        int i = a == null ? -1 : a.LastIndexOf(':');
        IPAddress ip;
        int port;
        if (i < 0 || !IPAddress.TryParse(a.Substring(0, i), out ip) || !int.TryParse(a.Substring(i + 1), out port)) return null;
        return new IPEndPoint(ip, port);
    }

    static string LanIp()
    {
        try
        {
            using (Socket s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp))
            {
                s.Connect("192.0.2.1", 9);   // nothing is sent; this only picks the outgoing address
                return ((IPEndPoint)s.LocalEndPoint).Address.ToString();
            }
        }
        catch { return null; }
    }

    /**
     * Leaves a note for the phone on the board and returns its decrypted answer (the same kind and
     * session), or null. Listens before posting, so the answer cannot be missed. Throws IOException
     * when the board cannot be reached.
     */
    static string Ask(byte[] psk, string note, string kind, string sHex, int waitMs)
    {
        ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
        string answer = null;
        HttpWebRequest sub = (HttpWebRequest)WebRequest.Create(Board + "/" + Topic(psk, "L87P/1 down") + "/json");
        sub.Timeout = waitMs + 1000;
        sub.ReadWriteTimeout = waitMs + 1000;
        try
        {
            using (WebResponse r = sub.GetResponse())
            using (StreamReader rd = new StreamReader(r.GetResponseStream(), Encoding.UTF8))
            {
                rd.ReadLine();   // the board's "open"
                HttpWebRequest post = (HttpWebRequest)WebRequest.Create(Board + "/" + Topic(psk, "L87P/1 up"));
                post.Method = "POST";
                post.Timeout = 10000;
                post.Headers["Cache"] = "no";
                post.Headers["Firebase"] = "no";
                byte[] body = Encoding.ASCII.GetBytes(Seal(psk, note));
                using (Stream w = post.GetRequestStream()) w.Write(body, 0, body.Length);
                post.GetResponse().Close();
                int end = Environment.TickCount + waitMs;
                while (answer == null && unchecked(Environment.TickCount - end) < 0)
                {
                    string line = rd.ReadLine();
                    if (line == null) break;
                    if (!line.Contains("\"event\":\"message\"")) continue;
                    Match m = Regex.Match(line, "\"message\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"");
                    string a = m.Success ? Unseal(psk, m.Groups[1].Value) : null;
                    if (a != null && a.Contains("\"t\":\"" + kind + "\"") && a.Contains("\"s\":\"" + sHex + "\"")) answer = a;
                }
            }
        }
        catch (Exception e)
        {
            if (answer == null) throw new IOException("could not reach the board at " + Board + " (" + e.Message + ")");
        }
        return answer;
    }

    static string NewSession()
    {
        byte[] session = new byte[8];
        Rand(session);
        StringBuilder sh = new StringBuilder();
        foreach (byte b in session) sh.Append(b.ToString("x2"));
        return sh.ToString();
    }

    static int Now() { return (int)(DateTime.UtcNow - new DateTime(1970, 1, 1)).TotalSeconds; }

    /** The phone's current addresses, asked through the board (its IPv6 changes when mobile data reconnects). Empty when it does not answer. */
    public static List<string> Where(byte[] psk)
    {
        List<string> addrs = new List<string>();
        string sHex = NewSession();
        string a;
        try { a = Ask(psk, "{\"t\":\"where\",\"s\":\"" + sHex + "\",\"at\":" + Now() + "}", "where", sHex, 6000); }
        catch { return addrs; }
        if (a == null) return addrs;
        Match m = Regex.Match(a, "\"addrs\"\\s*:\\s*\\[([^\\]]*)\\]");
        if (m.Success) foreach (Match x in Regex.Matches(m.Groups[1].Value, "\"([^\"]+)\"")) addrs.Add(x.Groups[1].Value);
        return addrs;
    }

    /**
     * Across IPv4: a note to the phone through the board, its answer, then the punch, then the same
     * handshake as Dial over the punched path. Throws IOException with the reason when it cannot.
     */
    public static Conn DialPunched(byte[] tid, byte[] psk, string name, Action<List<string>> onAddrs, Action<string> log)
    {
        ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
        Socket sock = NewUdp();
        try
        {
            IPEndPoint a1 = Stun(sock, "stun.l.google.com", 19302), a2 = Stun(sock, "stun.cloudflare.com", 3478);
            IPEndPoint me = a1 != null ? a1 : a2;
            if (me == null) throw new IOException("this network gives no public IPv4 address to punch from (STUN did not answer)");
            bool hard = a1 != null && a2 != null && a1.Port != a2.Port;
            string sHex = NewSession();
            byte[] session = new byte[8];
            for (int i = 0; i < 8; i++) session[i] = Convert.ToByte(sHex.Substring(i * 2, 2), 16);
            string lan = LanIp();
            int at = Now();
            string note = "{\"t\":\"punch\",\"s\":\"" + sHex + "\",\"at\":" + at + ",\"addr\":\"" + me + "\",\"hard\":" + (hard ? "true" : "false") +
                ",\"lan\":[" + (lan != null ? "\"" + lan + ":" + ((IPEndPoint)sock.LocalEndPoint).Port + "\"" : "") + "]}";
            string answer = Ask(psk, note, "punch", sHex, 12000);
            if (answer == null) throw new IOException("the phone did not answer through the board (is it on, with From other networks on?)");
            List<IPEndPoint> theirs = new List<IPEndPoint>();
            IPEndPoint pub = ParseAddr(Regex.Match(answer, "\"addr\"\\s*:\\s*\"([^\"]+)\"").Groups[1].Value);
            if (pub != null) theirs.Add(pub);
            Match lm = Regex.Match(answer, "\"lan\"\\s*:\\s*\\[([^\\]]*)\\]");
            if (lm.Success) foreach (Match x in Regex.Matches(lm.Groups[1].Value, "\"([^\"]+)\"")) { IPEndPoint e = ParseAddr(x.Groups[1].Value); if (e != null) theirs.Add(e); }
            if (theirs.Count == 0) throw new IOException("the phone could not see its own public address");
            bool hardThere = Regex.IsMatch(answer, "\"hard\"\\s*:\\s*true");
            byte[] k = Hmac(psk, Ascii("L87P/1 udp"), session);
            byte[] toPhone = Hmac(k, Ascii("dev")), toDev = Hmac(k, Ascii("phone"));
            List<string> shown = new List<string>();
            foreach (IPEndPoint t in theirs) shown.Add(t.ToString());
            if (log != null) log("Punching to " + string.Join(", ", shown.ToArray()) + " (here " + me + ", hard here " + hard + ", there " + hardThere + ")");
            PathFound path = Knock(sock, hard, theirs, hardThere, toPhone, toDev, 0);
            if (path == null)
                throw new IOException("no way through the two networks' NATs (" + (hard ? "hard" : "easy") + " here, " + (hardThere ? "hard" : "easy") + " there)");
            if (log != null) log("Punched through to " + path.to);
            Udp87 u = new Udp87(path.s, path.to, toPhone, toDev, 0);
            return Handshake(u, u.Close, tid, psk, name, onAddrs, log);
        }
        catch
        {
            try { sock.Close(); } catch { }
            throw;
        }
    }

    /**
     * A reliable, ordered stream of bytes over one UDP path, for the tunnel to run on: numbered
     * packets, cumulative and selective acks, loss found by time (a later packet came, this one did
     * not), resending, and CUBIC's window. The same as the phone's UdpCarrier.
     */
    public class Udp87 : Stream
    {
        class Sent { public int seq; public byte[] data; public int at; public int tries; }

        readonly Socket sock;
        volatile IPEndPoint peer;
        readonly Tagger seal, check;
        readonly int role;
        readonly object gate = new object();
        readonly object order = new object();
        int again;
        volatile bool open = true;

        readonly Queue<byte[]> pending = new Queue<byte[]>();
        readonly SortedDictionary<int, Sent> inflight = new SortedDictionary<int, Sent>();
        int nextSeq, recover, peerWnd = PWINDOW;
        double cwnd = 16, ssthresh = 1e9, srtt, rttvar, minRtt, wmax, cubicK, origin;
        int rto = 1000, epoch = -1;
        bool epochSet;
        volatile int lastSent = Environment.TickCount, lastHeard = Environment.TickCount;

        int expected, unacked;
        readonly Dictionary<int, byte[]> early = new Dictionary<int, byte[]>();
        readonly Queue<byte[]> chunks = new Queue<byte[]>();
        byte[] cur;
        int pos;
        int readTimeout = Timeout.Infinite;

        public Udp87(Socket sock, IPEndPoint peer, byte[] tx, byte[] rx, int role)
        {
            this.sock = sock; this.peer = peer; this.role = role;
            seal = new Tagger(tx); check = new Tagger(rx);
            sock.ReceiveTimeout = 200;
            Thread r = new Thread(RecvLoop); r.IsBackground = true; r.Start();
            Thread t = new Thread(TickLoop); t.IsBackground = true; t.Start();
        }

        public override bool CanRead { get { return true; } }
        public override bool CanWrite { get { return true; } }
        public override bool CanSeek { get { return false; } }
        public override bool CanTimeout { get { return true; } }
        public override int ReadTimeout { get { return readTimeout; } set { readTimeout = value; } }
        public override long Length { get { throw new NotSupportedException(); } }
        public override long Position { get { throw new NotSupportedException(); } set { throw new NotSupportedException(); } }
        public override long Seek(long o, SeekOrigin s) { throw new NotSupportedException(); }
        public override void SetLength(long v) { throw new NotSupportedException(); }
        public override void Flush() { }

        public override int Read(byte[] b, int off, int count)
        {
            if (count == 0) return 0;
            lock (gate)
            {
                if (cur == null || pos >= cur.Length)
                {
                    int end = Environment.TickCount + readTimeout;
                    while (chunks.Count == 0 && open)
                    {
                        if (readTimeout == Timeout.Infinite) Monitor.Wait(gate, 1000);
                        else
                        {
                            int left = end - Environment.TickCount;
                            if (left <= 0) throw new IOException("nothing in " + readTimeout + " ms");
                            Monitor.Wait(gate, left);
                        }
                    }
                    if (chunks.Count == 0) return 0;
                    cur = chunks.Dequeue();
                    pos = 0;
                }
                int n = Math.Min(count, cur.Length - pos);
                Array.Copy(cur, pos, b, off, n);
                pos += n;
                return n;
            }
        }

        public override void Write(byte[] b, int off, int count)
        {
            for (int p = off; p < off + count; p += MSS)
            {
                int n = Math.Min(MSS, off + count - p);
                byte[] chunk = new byte[n];
                Array.Copy(b, p, chunk, 0, n);
                lock (gate)
                {
                    while (open && pending.Count + inflight.Count >= PQUEUE) Monitor.Wait(gate, 1000);
                    if (!open) throw new IOException("the path is closed");
                    pending.Enqueue(chunk);
                }
            }
            Pump();
        }

        long SackBits()
        {
            long bits = 0;
            for (int i = 0; i < 64; i++) if (early.ContainsKey(expected + 1 + i)) bits |= 1L << i;
            return bits;
        }

        void Send(int kind, int seq, byte[] payload)
        {
            int ack, wnd;
            long sack;
            lock (gate)
            {
                ack = expected; sack = SackBits(); wnd = Math.Max(0, PWINDOW - early.Count);
                if (kind == PDATA || kind == PACK || kind == PKEEP) unacked = 0;
            }
            byte[] pk = seal.Packet(kind, role, seq, ack, sack, wnd, payload, 0, payload.Length);
            try { sock.SendTo(pk, peer); } catch { }
            lastSent = Environment.TickCount;
        }

        /** Sends what the window allows: one thread at a time, so packets leave in order. */
        void Pump()
        {
            Interlocked.Exchange(ref again, 1);
            while (Interlocked.CompareExchange(ref again, 0, 0) == 1)
            {
                if (!Monitor.TryEnter(order)) return;
                try
                {
                    Interlocked.Exchange(ref again, 0);
                    List<Sent> outq = new List<Sent>();
                    lock (gate)
                    {
                        int room = Math.Min((int)cwnd, peerWnd) - inflight.Count;
                        while (outq.Count < room && outq.Count < 256 && pending.Count > 0)
                        {
                            Sent s = new Sent();
                            s.seq = nextSeq++; s.data = pending.Dequeue(); s.at = Environment.TickCount;
                            inflight[s.seq] = s;
                            outq.Add(s);
                        }
                    }
                    foreach (Sent s in outq) Send(PDATA, s.seq, s.data);
                }
                finally { Monitor.Exit(order); }
            }
        }

        void RecvLoop()
        {
            byte[] buf = new byte[2048];
            while (open)
            {
                EndPoint from = new IPEndPoint(IPAddress.Any, 0);
                int n;
                try { n = sock.ReceiveFrom(buf, ref from); }
                catch (SocketException e) { if (e.SocketErrorCode == SocketError.TimedOut || e.SocketErrorCode == SocketError.ConnectionReset) continue; break; }
                catch { break; }
                if (!check.Valid(buf, n) || buf[1] == role) continue;
                lastHeard = Environment.TickCount;
                IPEndPoint f = (IPEndPoint)from;
                if (!f.Equals(peer)) peer = f;   // the other end's NAT moved it
                int kind = buf[0], seq = Be(buf, 2), ack = Be(buf, 6), wnd = (buf[18] << 8) | buf[19];
                long sack = ((long)Be(buf, 10) << 32) | (uint)Be(buf, 14);
                if (kind == PROBE) { try { sock.SendTo(seal.Packet(PROBE_ACK, role, 0, 0, 0, 0, new byte[0], 0, 0), f); } catch { } }
                else if (kind == PCLOSE) { Finish("the other end closed the path"); return; }
                else if (kind == PDATA || kind == PACK || kind == PKEEP)
                {
                    Acked(ack, sack, wnd);
                    if (kind == PDATA) Data(seq, Sub(buf, PHEADER, n - PHEADER - PTAG));
                }
            }
        }

        void Data(int seq, byte[] payload)
        {
            bool now;
            lock (gate)
            {
                if (seq - expected < 0) now = true;   // a copy of one already had
                else if (seq == expected)
                {
                    chunks.Enqueue(payload);
                    expected++;
                    bool filled = early.Count > 0;
                    byte[] e;
                    while (early.TryGetValue(expected, out e)) { early.Remove(expected); chunks.Enqueue(e); expected++; }
                    unacked++;
                    now = filled || unacked >= 2;
                    Monitor.PulseAll(gate);
                }
                else if (seq - expected < PWINDOW) { early[seq] = payload; now = true; }
                else now = false;
            }
            if (now) Send(PACK, 0, new byte[0]);
        }

        void Acked(int ack, long sack, int wnd)
        {
            List<Sent> resend = new List<Sent>();
            lock (gate)
            {
                peerWnd = Math.Max(wnd, 4);
                int now = Environment.TickCount, newly = 0, sample = -1, latest = 0;
                bool cumulative = false, any = false;
                List<int> done = new List<int>();
                foreach (KeyValuePair<int, Sent> kv in inflight) { if (kv.Key - ack >= 0) break; done.Add(kv.Key); }
                foreach (int s in done)
                {
                    Sent x = inflight[s];
                    inflight.Remove(s);
                    if (x.tries == 0) sample = now - x.at;
                    if (!any || x.at - latest > 0) latest = x.at;
                    any = true; newly++; cumulative = true;
                }
                for (int i = 0; i < 64; i++)
                {
                    if ((sack & (1L << i)) == 0) continue;
                    Sent x;
                    if (inflight.TryGetValue(ack + 1 + i, out x))
                    {
                        inflight.Remove(ack + 1 + i);
                        if (x.tries == 0) sample = now - x.at;
                        if (!any || x.at - latest > 0) latest = x.at;
                        any = true; newly++;
                    }
                }
                if (sample >= 0)
                {
                    if (srtt == 0) { srtt = sample; rttvar = sample / 2.0; }
                    else { rttvar = 0.75 * rttvar + 0.25 * Math.Abs(srtt - sample); srtt = 0.875 * srtt + 0.125 * sample; }
                    minRtt = minRtt == 0 ? sample : Math.Min(minRtt, sample);
                }
                if (cumulative) rto = (int)Math.Min(3000, srtt + Math.Max(4 * rttvar, 200));   // Linux: at least 200 ms over the round trip
                Grow(newly, now);
                // A packet sent after this one has come and this one has not (allowing a little
                // reordering): lost, send it again. Originals leave in order, so the first one never
                // resent and sent too late to count ends the search.
                if (any)
                {
                    int reo = (int)Math.Max(srtt / 4, 5);
                    foreach (Sent x in inflight.Values)
                    {
                        if (resend.Count >= 64) break;
                        if (latest - reo - x.at > 0) resend.Add(x);
                        else if (x.tries == 0) break;
                    }
                }
                if (resend.Count > 0)
                {
                    if (resend[0].seq - recover >= 0)
                    {
                        wmax = cwnd; epochSet = false;
                        ssthresh = Math.Max(cwnd * 0.7, 8); cwnd = ssthresh; recover = nextSeq;
                    }
                    foreach (Sent x in resend) { x.at = now; x.tries++; }
                }
                Monitor.PulseAll(gate);
            }
            foreach (Sent x in resend) Send(PDATA, x.seq, x.data);
            Pump();
        }

        /** As Linux: doubling until the first loss, then CUBIC; never slower than Reno. Under gate. */
        void Grow(int n, int now)
        {
            if (n == 0) return;
            if (cwnd < ssthresh) cwnd += n;
            else
            {
                if (!epochSet)
                {
                    epochSet = true; epoch = now;
                    cubicK = Math.Pow(Math.Max(0, wmax - cwnd) / 0.4, 1 / 3.0);
                    origin = Math.Max(wmax, cwnd);
                }
                double t = (now - epoch + minRtt) / 1000.0;
                double target = origin + 0.4 * Math.Pow(t - cubicK, 3);
                cwnd += n * Math.Max(target - cwnd, 1.0) / cwnd;
            }
            cwnd = Math.Min(cwnd, 2048);
        }

        void TickLoop()
        {
            while (open)
            {
                Thread.Sleep(10);
                int now = Environment.TickCount;
                Sent first = null;
                bool ackDue;
                lock (gate)
                {
                    // Nothing back for the oldest packet in a whole timeout: send it again, and
                    // start slow (once per loss; the acks for it then show what else is missing).
                    foreach (Sent x in inflight.Values) { first = x; break; }
                    if (first != null && now - first.at > rto)
                    {
                        if (first.seq - recover >= 0) { wmax = cwnd; epochSet = false; ssthresh = Math.Max(cwnd * 0.7, 8); cwnd = 8; recover = nextSeq; }
                        rto = Math.Min(rto * 2, 5000);
                        first.at = now; first.tries++;
                    }
                    else first = null;
                    ackDue = unacked > 0;
                }
                if (first != null) Send(PDATA, first.seq, first.data);
                if (ackDue) Send(PACK, 0, new byte[0]);
                Pump();
                if (now - lastSent > 5000) Send(PKEEP, 0, new byte[0]);
                if (now - lastHeard > 60000) Finish("nothing from the other end for 60 s");
            }
        }

        void Finish(string why)
        {
            lock (gate)
            {
                if (!open) return;
                open = false;
                Monitor.PulseAll(gate);
            }
            try { sock.Close(); } catch { }
        }

        public override void Close()
        {
            if (open) for (int i = 0; i < 3; i++) Send(PCLOSE, 0, new byte[0]);
            Finish("closed here");
            base.Close();
        }
    }

    public class Conn
    {
        readonly Action closer;
        readonly Stream ns;
        readonly Cipher tx, rx;
        readonly Action<List<string>> onAddrs;
        readonly Action<string> log;
        readonly object sendLock = new object();
        long nextTicket, serving;
        readonly Dictionary<int, Stream87> streams = new Dictionary<int, Stream87>();
        int nextSid = 1;
        volatile bool alive = true;
        int lastRx = Environment.TickCount, lastTx = Environment.TickCount;
        internal readonly ManualResetEvent hello = new ManualResetEvent(false);

        internal Conn(Action closer, Stream ns, Cipher tx, Cipher rx, Action<List<string>> onAddrs, Action<string> log)
        {
            this.closer = closer; this.ns = ns; this.tx = tx; this.rx = rx; this.onAddrs = onAddrs; this.log = log;
        }

        public bool Alive { get { return alive; } }

        internal void Start()
        {
            Thread r = new Thread(Run);
            r.IsBackground = true;
            r.Start();
            Thread k = new Thread(Keepalive);
            k.IsBackground = true;
            k.Start();
        }

        public void Send(int type, int sid, byte[] body) { Send(type, sid, body, 0, body.Length); }

        public void Send(int type, int sid, byte[] body, int off, int len)
        {
            byte[] plain = new byte[5 + len];
            plain[0] = (byte)type;
            plain[1] = (byte)(sid >> 24); plain[2] = (byte)(sid >> 16); plain[3] = (byte)(sid >> 8); plain[4] = (byte)sid;
            Array.Copy(body, off, plain, 5, len);
            // Streams take turns in the order they asked: on a slow link the one that just wrote
            // would otherwise take the connection straight back while the others wait.
            long mine;
            lock (sendLock)
            {
                mine = nextTicket++;
                while (serving != mine) Monitor.Wait(sendLock);
            }
            try
            {
                if (!alive) throw new IOException("the tunnel is closed");
                byte[] frame = tx.Seal(plain);
                try { ns.Write(frame, 0, frame.Length); }
                catch (Exception e) { Close("write failed"); throw new IOException(e.Message); }
                lastTx = Environment.TickCount;
            }
            finally
            {
                lock (sendLock) { serving++; Monitor.PulseAll(sendLock); }
            }
        }

        public void OpenStream(TcpClient local, int port)
        {
            Stream87 st;
            lock (streams)
            {
                st = new Stream87(this, nextSid, local);
                streams[nextSid] = st;
                nextSid += 2;
            }
            Send(OPEN, st.sid, new byte[] { (byte)(port >> 8), (byte)port });
            st.Start();
        }

        internal void Forget(int sid) { lock (streams) streams.Remove(sid); }

        void Run()
        {
            string why = "closed";
            try
            {
                while (alive)
                {
                    int len = Be(ReadExact(ns, 4), 0);
                    if (len < 5 || len > 65536 + 5) { why = "a frame of " + len + " bytes"; break; }
                    byte[] ct = ReadExact(ns, len), tag = ReadExact(ns, 16);
                    if (!rx.Open(ct, tag)) { why = "a frame failed its check"; break; }
                    lastRx = Environment.TickCount;
                    int type = ct[0], sid = Be(ct, 1);
                    byte[] body = new byte[len - 5];
                    Array.Copy(ct, 5, body, 0, body.Length);
                    Stream87 st;
                    lock (streams) streams.TryGetValue(sid, out st);
                    if (type == DATA && st != null) st.Deliver(body);
                    else if (type == FIN && st != null) st.Deliver(null);
                    else if (type == CREDIT && st != null && body.Length >= 4) st.Credit(Be(body, 0));
                    else if (type == RST && st != null) st.Reset(null, false);
                    else if (type == PING) Send(PONG, 0, body);
                    else if (type == HELLO || type == ADDR)
                    {
                        string j = Encoding.UTF8.GetString(body);
                        Match m = Regex.Match(j, "\"addrs\"\\s*:\\s*\\[([^\\]]*)\\]");
                        if (m.Success && onAddrs != null)
                        {
                            List<string> list = new List<string>();
                            foreach (Match a in Regex.Matches(m.Groups[1].Value, "\"([^\"]+)\"")) list.Add(a.Groups[1].Value);
                            try { onAddrs(list); } catch { }
                        }
                        hello.Set();
                    }
                    else if (type == BYE) { why = "the phone closed the tunnel: " + Encoding.UTF8.GetString(body); break; }
                }
            }
            catch (Exception e) { why = e.Message; }
            Close(why);
        }

        void Keepalive()
        {
            Random rnd = new Random();
            while (alive)
            {
                Thread.Sleep(5000);
                if (unchecked(Environment.TickCount - lastRx) > 60000) Close("nothing from the phone for 60 s");
                else if (unchecked(Environment.TickCount - lastTx) > 20000)
                {
                    byte[] b = new byte[8];
                    rnd.NextBytes(b);
                    try { Send(PING, 0, b); } catch { }
                }
            }
        }

        public void Close(string why)
        {
            Stream87[] held;
            lock (streams)
            {
                if (!alive) return;
                alive = false;
                held = new Stream87[streams.Count];
                streams.Values.CopyTo(held, 0);
            }
            if (log != null) log("Tunnel closed: " + why);
            foreach (Stream87 s in held) s.Reset(null, false);
            try { closer(); } catch { }
        }
    }

    /** A local connection carried as one stream: up from the socket, down to it through a queue. */
    internal class Stream87
    {
        readonly Conn c;
        internal readonly int sid;
        readonly TcpClient local;
        readonly object gate = new object();
        readonly Queue<byte[]> queue = new Queue<byte[]>();
        static readonly byte[] FinMark = new byte[0];
        long window = WINDOW;
        int queued, ends;
        bool closed;

        public Stream87(Conn c, int sid, TcpClient local) { this.c = c; this.sid = sid; this.local = local; }

        public void Start()
        {
            local.NoDelay = true;
            Thread u = new Thread(Up); u.IsBackground = true; u.Start();
            Thread d = new Thread(Down); d.IsBackground = true; d.Start();
        }

        void Up()
        {
            byte[] buf = new byte[CHUNK];
            try
            {
                NetworkStream s = local.GetStream();
                while (true)
                {
                    int n = s.Read(buf, 0, buf.Length);
                    if (n <= 0) { c.Send(FIN, sid, new byte[0]); End(); return; }
                    lock (gate)
                    {
                        while (window < n && !closed) Monitor.Wait(gate, 5000);
                        if (closed) return;
                        window -= n;
                    }
                    c.Send(DATA, sid, buf, 0, n);
                }
            }
            catch { Reset("the local connection failed", true); }
        }

        void Down()
        {
            int owed = 0;
            try
            {
                NetworkStream s = local.GetStream();
                while (true)
                {
                    byte[] data;
                    bool empty;
                    lock (gate)
                    {
                        while (queue.Count == 0 && !closed) Monitor.Wait(gate);
                        if (queue.Count == 0) return;
                        data = queue.Dequeue();
                        queued -= data.Length;
                        empty = queue.Count == 0;
                    }
                    if (object.ReferenceEquals(data, FinMark)) { try { local.Client.Shutdown(SocketShutdown.Send); } catch { } End(); return; }
                    s.Write(data, 0, data.Length);
                    owed += data.Length;
                    if (owed >= CREDIT_STEP || (empty && owed > 0))
                    {
                        c.Send(CREDIT, sid, new byte[] { (byte)(owed >> 24), (byte)(owed >> 16), (byte)(owed >> 8), (byte)owed });
                        owed = 0;
                    }
                }
            }
            catch { Reset("the local connection failed", true); }
        }

        public void Deliver(byte[] data)
        {
            lock (gate)
            {
                if (closed) return;
                if (data != null && queued + data.Length > WINDOW) throw new IOException("the phone sent past the window");
                queue.Enqueue(data ?? FinMark);
                if (data != null) queued += data.Length;
                Monitor.PulseAll(gate);
            }
        }

        public void Credit(int n) { lock (gate) { window += n; Monitor.PulseAll(gate); } }

        void End()
        {
            lock (gate)
            {
                ends++;
                if (ends < 2 || closed) return;
                closed = true;
                Monitor.PulseAll(gate);
            }
            try { local.Close(); } catch { }
            c.Forget(sid);
        }

        public void Reset(string why, bool send)
        {
            lock (gate)
            {
                if (closed) return;
                closed = true;
                Monitor.PulseAll(gate);
            }
            if (send) try { c.Send(RST, sid, Encoding.UTF8.GetBytes(why ?? "")); } catch { }
            try { local.Close(); } catch { }
            c.Forget(sid);
        }
    }
}

/**
 * The monitors on the desktop, read from Windows each time (never kept), in real pixels; and the
 * calls that put a virtual display on the desktop, take it off, and choose the main monitor.
 */
public static class Displays
{
    public class Mon
    {
        public string Device;
        /** Virtual: the virtual display on its own. Cloned: a real screen the virtual one duplicates. */
        public bool Primary, Virtual, Cloned;
        public int X, Y, W, H, Hz;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct DISPLAY_DEVICE
    {
        public int cb;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceString;
        public int StateFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceID;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceKey;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct DEVMODE
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
        public int dmFields, dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput;
        public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public short dmLogPixels;
        public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
        public int dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool EnumDisplayDevices(string device, uint index, ref DISPLAY_DEVICE dd, uint flags);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool EnumDisplaySettings(string device, int mode, ref DEVMODE dm);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int ChangeDisplaySettingsEx(string device, ref DEVMODE dm, IntPtr hwnd, uint flags, IntPtr param);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int ChangeDisplaySettingsEx(string device, IntPtr dm, IntPtr hwnd, uint flags, IntPtr param);
    [DllImport("user32.dll")] static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
    [DllImport("user32.dll")] static extern int SetDisplayConfig(uint paths, IntPtr pathArray, uint modes, IntPtr modeArray, uint flags);

    const int ATTACHED = 0x1, PRIMARY = 0x4, MIRRORING = 0x8, MONITOR_ACTIVE = 0x1;
    const uint SDC_TOPOLOGY_CLONE = 0x2, SDC_TOPOLOGY_EXTEND = 0x4, SDC_APPLY = 0x80;
    const int CURRENT = -1, REGISTRY = -2;
    const int DM_POSITION = 0x20, DM_PELSWIDTH = 0x80000, DM_PELSHEIGHT = 0x100000;
    const uint CDS_UPDATEREGISTRY = 0x1, CDS_NORESET = 0x10000000;

    /** What virtual-display drivers call themselves: IddSample and its forks (MTT's Virtual Display Driver), Parsec's, spacedesk's, Amyuni's. */
    static readonly string[] VirtualNames = { "virtual", "indirect", "iddsample", "mtt1337", "parsec", "spacedesk", "usbmmidd" };

    static bool IsVirtual(string s)
    {
        if (string.IsNullOrEmpty(s)) return false;
        s = s.ToLowerInvariant();
        foreach (string v in VirtualNames) if (s.Contains(v)) return true;
        return false;
    }

    static DISPLAY_DEVICE NewDevice() { var d = new DISPLAY_DEVICE(); d.cb = Marshal.SizeOf(typeof(DISPLAY_DEVICE)); return d; }
    static DEVMODE NewMode() { var m = new DEVMODE(); m.dmSize = (short)Marshal.SizeOf(typeof(DEVMODE)); return m; }

    /**
     * The monitors on an output (only those showing a picture, if "active"): whether any is
     * virtual, and whether any is real. Duplicated screens are one output with two monitors.
     */
    static void MonitorsOf(string output, bool active, out bool virt, out bool real)
    {
        virt = false; real = false;
        for (uint j = 0; j < 8; j++)
        {
            var m = NewDevice();
            if (!EnumDisplayDevices(output, j, ref m, 0)) break;
            if (active && (m.StateFlags & MONITOR_ACTIVE) == 0) continue;
            if (IsVirtual(m.DeviceID) || IsVirtual(m.DeviceString)) virt = true; else real = true;
        }
    }

    /** Every monitor that is part of the desktop now. */
    public static List<Mon> Attached()
    {
        var list = new List<Mon>();
        for (uint i = 0; i < 64; i++)
        {
            var a = NewDevice();
            if (!EnumDisplayDevices(null, i, ref a, 0)) break;
            if ((a.StateFlags & ATTACHED) == 0 || (a.StateFlags & MIRRORING) != 0) continue;
            var dm = NewMode();
            if (!EnumDisplaySettings(a.DeviceName, CURRENT, ref dm) || dm.dmPelsWidth <= 0) continue;
            bool virt, real; MonitorsOf(a.DeviceName, true, out virt, out real);
            var m = new Mon();
            m.Device = a.DeviceName;
            m.Primary = (a.StateFlags & PRIMARY) != 0;
            // An output showing a real screen and the virtual one is Windows' "Duplicate": not a second screen.
            m.Virtual = IsVirtual(a.DeviceString) || (virt && !real);
            m.Cloned = virt && real;
            m.X = dm.dmPositionX; m.Y = dm.dmPositionY; m.W = dm.dmPelsWidth; m.H = dm.dmPelsHeight; m.Hz = dm.dmDisplayFrequency;
            list.Add(m);
        }
        return list;
    }

    /** Whether a virtual-display driver is installed, on the desktop or not. */
    public static bool VirtualInstalled()
    {
        for (uint i = 0; i < 64; i++)
        {
            var a = NewDevice();
            if (!EnumDisplayDevices(null, i, ref a, 0)) break;
            bool virt, real; MonitorsOf(a.DeviceName, false, out virt, out real);
            if (IsVirtual(a.DeviceString) || virt) return true;
        }
        return false;
    }

    /** A virtual display that is installed, with its monitor, but not on the desktop. */
    public static string DetachedVirtual()
    {
        for (uint i = 0; i < 64; i++)
        {
            var a = NewDevice();
            if (!EnumDisplayDevices(null, i, ref a, 0)) break;
            if ((a.StateFlags & (ATTACHED | MIRRORING)) != 0) continue;
            bool virt, real; MonitorsOf(a.DeviceName, false, out virt, out real);
            if (virt || (IsVirtual(a.DeviceString) && real)) return a.DeviceName;
        }
        return null;
    }

    /** Windows+P's "Extend": every connected screen, the virtual one included, becomes part of one desktop. */
    public static bool Extend()
    {
        return SetDisplayConfig(0, IntPtr.Zero, 0, IntPtr.Zero, SDC_TOPOLOGY_EXTEND | SDC_APPLY) == 0;
    }

    /** Windows+P's "Duplicate". */
    public static bool Duplicate()
    {
        return SetDisplayConfig(0, IntPtr.Zero, 0, IntPtr.Zero, SDC_TOPOLOGY_CLONE | SDC_APPLY) == 0;
    }

    /** The whole desktop, all monitors together. */
    public static Mon Desktop(List<Mon> mons)
    {
        var d = new Mon();
        if (mons.Count == 0) return d;
        int l = int.MaxValue, t = int.MaxValue, r = int.MinValue, b = int.MinValue;
        foreach (var m in mons) { l = Math.Min(l, m.X); t = Math.Min(t, m.Y); r = Math.Max(r, m.X + m.W); b = Math.Max(b, m.Y + m.H); }
        d.X = l; d.Y = t; d.W = r - l; d.H = b - t;
        return d;
    }

    /**
     * Of the display's own sizes, the one that suits the phone (w x h, landscape): the nearest
     * shape first, so the phone is filled, then the nearest height, so it is sharp but not tiny.
     */
    static bool BestSize(string dev, int w, int h, out int bw, out int bh)
    {
        bw = 0; bh = 0;
        double want = h > 0 ? (double)w / h : 16.0 / 9, best = double.MaxValue;
        for (int i = 0; i < 1000; i++)
        {
            var dm = NewMode();
            if (!EnumDisplaySettings(dev, i, ref dm)) break;
            int mw = dm.dmPelsWidth, mh = dm.dmPelsHeight;
            if (mw < 640 || mh < 360 || mw > 3840 || mh > 2160 || mw < mh) continue; // the phone decodes up to 4K
            double score = Math.Abs(Math.Log((double)mw / mh / want)) * 4 + Math.Abs(Math.Log((double)mh / Math.Max(360, h)));
            if (score < best) { best = score; bw = mw; bh = mh; }
        }
        if (bw > 0) return true;
        var reg = NewMode();
        if (EnumDisplaySettings(dev, REGISTRY, ref reg) && reg.dmPelsWidth > 0) { bw = reg.dmPelsWidth; bh = reg.dmPelsHeight; return true; }
        bw = 1920; bh = 1080;
        return true;
    }

    static bool Apply() { return ChangeDisplaySettingsEx(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero) == 0; }

    const int DM_DISPLAYFREQUENCY = 0x400000;

    /** A monitor's modes, each {width, height, refresh rate}, once each. */
    public static List<int[]> Modes(string dev)
    {
        var list = new List<int[]>();
        var seen = new HashSet<long>();
        for (int i = 0; i < 5000; i++)
        {
            var dm = NewMode();
            if (!EnumDisplaySettings(dev, i, ref dm)) break;
            long key = ((long)dm.dmPelsWidth << 32) | ((long)dm.dmPelsHeight << 12) | (uint)dm.dmDisplayFrequency;
            if (dm.dmBitsPerPel >= 24 && seen.Add(key)) list.Add(new int[] { dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency });
        }
        return list;
    }

    /** Sets a monitor's size and refresh rate, keeping its place. */
    public static bool SetMode(string dev, int w, int h, int hz)
    {
        var dm = NewMode();
        if (!EnumDisplaySettings(dev, CURRENT, ref dm)) return false;
        dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY;
        dm.dmPelsWidth = w; dm.dmPelsHeight = h; dm.dmDisplayFrequency = hz;
        if (ChangeDisplaySettingsEx(dev, ref dm, IntPtr.Zero, CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero) != 0) return false;
        return Apply();
    }

    /** Puts a display on the desktop, extended to the right of the others, at a size suiting the phone. */
    public static bool Attach(string dev, List<Mon> mons, int w, int h)
    {
        int bw, bh;
        BestSize(dev, w, h, out bw, out bh);
        int right = 0, top = 0;
        foreach (var m in mons) { right = Math.Max(right, m.X + m.W); if (m.Primary) top = m.Y; }
        var dm = NewMode();
        dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT;
        dm.dmPositionX = right; dm.dmPositionY = top;
        dm.dmPelsWidth = bw; dm.dmPelsHeight = bh;
        if (ChangeDisplaySettingsEx(dev, ref dm, IntPtr.Zero, CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero) != 0) return false;
        return Apply();
    }

    /** Takes a display off the desktop; Windows moves its windows onto the others. */
    public static bool Detach(string dev)
    {
        var mons = Attached();
        var m = mons.Find(x => x.Device == dev);
        if (m == null) return false;
        if (m.Primary)
        {
            var other = mons.Find(x => x.Device != dev);
            if (other == null || !MakePrimary(other.Device, mons)) return false;
        }
        var dm = NewMode();
        dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT; // all zero: off the desktop
        if (ChangeDisplaySettingsEx(dev, ref dm, IntPtr.Zero, CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero) != 0) return false;
        return Apply();
    }

    [DllImport("user32.dll")] static extern int GetDisplayConfigBufferSizes(uint flags, out uint paths, out uint modes);
    [DllImport("user32.dll")] static extern int QueryDisplayConfig(uint flags, ref uint paths, [In, Out] byte[] pathArray, ref uint modes, [In, Out] byte[] modeArray, IntPtr topology);
    [DllImport("user32.dll")] static extern int SetDisplayConfig(uint paths, [In] byte[] pathArray, uint modes, [In] byte[] modeArray, uint flags);

    /**
     * Makes a monitor the main one (the taskbar, new windows). The main monitor is the one at 0,0,
     * so every monitor moves by the same amount. Windows' current layout is changed as it stands
     * (DISPLAYCONFIG_PATH_INFO is 72 bytes, DISPLAYCONFIG_MODE_INFO 64; a source mode, type 1,
     * holds width, height and position at 16, 20, 28 and 32) and saved as this layout's own.
     * The older ChangeDisplaySettingsEx refuses to move the main monitor here.
     */
    public static bool MakePrimary(string dev, List<Mon> mons)
    {
        var np = mons.Find(x => x.Device == dev);
        if (np == null) return false;
        if (np.X == 0 && np.Y == 0) return true;
        const uint ACTIVE_ONLY = 2;
        uint np2, nm;
        if (GetDisplayConfigBufferSizes(ACTIVE_ONLY, out np2, out nm) != 0) return false;
        byte[] paths = new byte[np2 * 72], modes = new byte[nm * 64];
        if (QueryDisplayConfig(ACTIVE_ONLY, ref np2, paths, ref nm, modes, IntPtr.Zero) != 0) return false;
        bool found = false;
        for (int i = 0; i < nm; i++)
        {
            int o = i * 64;
            if (BitConverter.ToUInt32(modes, o) != 1) continue; // source modes only
            int x = BitConverter.ToInt32(modes, o + 28), y = BitConverter.ToInt32(modes, o + 32);
            if (x == np.X && y == np.Y && BitConverter.ToInt32(modes, o + 16) == np.W) found = true;
            Buffer.BlockCopy(BitConverter.GetBytes(x - np.X), 0, modes, o + 28, 4);
            Buffer.BlockCopy(BitConverter.GetBytes(y - np.Y), 0, modes, o + 32, 4);
        }
        if (!found) return false;
        const uint USE_SUPPLIED = 0x20, SAVE = 0x200, ALLOW_CHANGES = 0x400;
        return SetDisplayConfig(np2, paths, nm, modes, SDC_APPLY | USE_SUPPLIED | SAVE | ALLOW_CHANGES) == 0;
    }

    [DllImport("user32.dll")] static extern int DisplayConfigGetDeviceInfo([In, Out] byte[] packet);
    [DllImport("user32.dll")] static extern int DisplayConfigSetDeviceInfo([In] byte[] packet);

    /**
     * The target (adapter LUID and id, 12 bytes) of the path showing a monitor, for the HDR
     * calls. Each packet starts with a 20-byte header: type, size, adapter LUID, id.
     */
    static byte[] TargetOf(string dev)
    {
        const uint ACTIVE_ONLY = 2;
        uint np, nm;
        if (GetDisplayConfigBufferSizes(ACTIVE_ONLY, out np, out nm) != 0) return null;
        byte[] paths = new byte[np * 72], modes = new byte[nm * 64];
        if (QueryDisplayConfig(ACTIVE_ONLY, ref np, paths, ref nm, modes, IntPtr.Zero) != 0) return null;
        for (int i = 0; i < np; i++)
        {
            int o = i * 72;
            byte[] name = Packet(1, 84, paths, o); // the source's GDI name
            if (DisplayConfigGetDeviceInfo(name) != 0) continue;
            if (!string.Equals(Encoding.Unicode.GetString(name, 20, 64).TrimEnd('\0'), dev, StringComparison.OrdinalIgnoreCase)) continue;
            byte[] t = new byte[12];
            Array.Copy(paths, o + 20, t, 0, 12);
            return t;
        }
        return null;
    }

    /** A packet for DisplayConfig*DeviceInfo: type and size, then an adapter LUID and id from "from". */
    static byte[] Packet(int type, int size, byte[] from, int at)
    {
        byte[] p = new byte[size];
        BitConverter.GetBytes(type).CopyTo(p, 0);
        BitConverter.GetBytes(size).CopyTo(p, 4);
        Array.Copy(from, at, p, 8, 12);
        return p;
    }

    /** Whether Windows' HDR ("Use HDR") is on for a monitor. */
    public static bool HdrOn(string dev)
    {
        byte[] t = TargetOf(dev);
        if (t == null) return false;
        byte[] info = Packet(9, 32, t, 0); // DISPLAYCONFIG_DEVICE_INFO_GET_ADVANCED_COLOR_INFO
        return DisplayConfigGetDeviceInfo(info) == 0 && (BitConverter.ToUInt32(info, 20) & 2) != 0;
    }

    /**
     * The brightness Windows gives ordinary (SDR) white on a monitor in HDR, in nits: the
     * "SDR content brightness" slider. 80 when it cannot be read (the slider's lowest).
     */
    public static double SdrWhiteNits(string dev)
    {
        byte[] t = TargetOf(dev);
        if (t == null) return 80;
        byte[] w = Packet(11, 24, t, 0); // DISPLAYCONFIG_DEVICE_INFO_GET_SDR_WHITE_LEVEL, in thousandths of 80 nits
        if (DisplayConfigGetDeviceInfo(w) != 0) return 80;
        uint level = BitConverter.ToUInt32(w, 20);
        return level > 0 ? level * 80.0 / 1000 : 80;
    }

    /** Turns Windows' HDR on or off for a monitor, as its switch in Settings does. */
    public static bool SetHdr(string dev, bool on)
    {
        // Tried again for a couple of seconds: just after a change of mode or layout, Windows
        // can refuse or quietly drop the request.
        for (int attempt = 0; attempt < 4; attempt++)
        {
            if (attempt > 0) Thread.Sleep(500);
            byte[] t = TargetOf(dev);
            if (t == null) continue;
            byte[] set = Packet(10, 24, t, 0); // DISPLAYCONFIG_DEVICE_INFO_SET_ADVANCED_COLOR_STATE
            BitConverter.GetBytes(on ? 1 : 0).CopyTo(set, 20);
            if (DisplayConfigSetDeviceInfo(set) != 0) continue;
            for (int i = 0; i < 20 && HdrOn(dev) != on; i++) Thread.Sleep(100);
            if (HdrOn(dev) == on) return true;
        }
        return false;
    }

    /** This thread works in real pixels until RestoreDpi; returns what it was. */
    public static IntPtr RealPixels()
    {
        try { return SetThreadDpiAwarenessContext(new IntPtr(-4)); } catch { return IntPtr.Zero; } // per-monitor v2
    }

    public static void RestoreDpi(IntPtr was)
    {
        if (was == IntPtr.Zero) return;
        try { SetThreadDpiAwarenessContext(was); } catch { }
    }
}

/**
 * Which graphics adapter, and which output on it, shows a monitor ("\\.\DISPLAY2"): what the
 * GPU screen capture (Desktop Duplication, ffmpeg's ddagrab) needs to capture exactly it. The
 * order of Windows' monitor list is not the adapters' order, so it is looked up by name.
 */
public static class Dxgi
{
    [DllImport("dxgi.dll")] static extern int CreateDXGIFactory1(ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out IDXGIFactory1 factory);

    [ComImport, Guid("770aae78-f26f-4dba-a829-253c83d1b387"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IDXGIFactory1
    {
        void SetPrivateData(); void SetPrivateDataInterface(); void GetPrivateData(); void GetParent();
        void EnumAdapters(); void MakeWindowAssociation(); void GetWindowAssociation(); void CreateSwapChain(); void CreateSoftwareAdapter();
        [PreserveSig] int EnumAdapters1(uint index, [MarshalAs(UnmanagedType.Interface)] out IDXGIAdapter1 adapter);
    }

    [ComImport, Guid("29038f61-3839-4626-91fd-086879011a05"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IDXGIAdapter1
    {
        void SetPrivateData(); void SetPrivateDataInterface(); void GetPrivateData(); void GetParent();
        [PreserveSig] int EnumOutputs(uint index, [MarshalAs(UnmanagedType.Interface)] out IDXGIOutput output);
        [PreserveSig] int GetDesc(out ADAPTER_DESC desc);
    }

    [ComImport, Guid("ae02eedb-c735-4690-8d52-5a8dc20213aa"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IDXGIOutput
    {
        void SetPrivateData(); void SetPrivateDataInterface(); void GetPrivateData(); void GetParent();
        [PreserveSig] int GetDesc(out OUTPUT_DESC desc);
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct ADAPTER_DESC
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Description;
        public uint VendorId, DeviceId, SubSysId, Revision;
        public UIntPtr DedicatedVideoMemory, DedicatedSystemMemory, SharedSystemMemory;
        public uint LuidLow; public int LuidHigh;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct OUTPUT_DESC
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
        public int Left, Top, Right, Bottom;
        public int AttachedToDesktop, Rotation;
        public IntPtr Monitor;
    }

    public static bool Find(string deviceName, out int adapter, out int output, out string adapterName)
    {
        adapter = -1; output = -1; adapterName = "";
        try
        {
            Guid g = typeof(IDXGIFactory1).GUID;
            IDXGIFactory1 f;
            if (CreateDXGIFactory1(ref g, out f) != 0) return false;
            for (uint a = 0; a < 16; a++)
            {
                IDXGIAdapter1 ad;
                if (f.EnumAdapters1(a, out ad) != 0) break;
                ADAPTER_DESC ades; ad.GetDesc(out ades);
                for (uint o = 0; o < 16; o++)
                {
                    IDXGIOutput op;
                    if (ad.EnumOutputs(o, out op) != 0) break;
                    OUTPUT_DESC od; op.GetDesc(out od);
                    if (string.Equals(od.DeviceName, deviceName, StringComparison.OrdinalIgnoreCase))
                    {
                        adapter = (int)a; output = (int)o; adapterName = ades.Description ?? "";
                        return true;
                    }
                }
            }
        }
        catch { }
        return false;
    }

    /** Every monitor DXGI knows, for diagnosis: "adapter/output name device". */
    public static string List()
    {
        var sb = new System.Text.StringBuilder();
        try
        {
            Guid g = typeof(IDXGIFactory1).GUID;
            IDXGIFactory1 f;
            if (CreateDXGIFactory1(ref g, out f) != 0) return "";
            for (uint a = 0; a < 16; a++)
            {
                IDXGIAdapter1 ad;
                if (f.EnumAdapters1(a, out ad) != 0) break;
                ADAPTER_DESC ades; ad.GetDesc(out ades);
                sb.Append(a + " " + ades.Description + "\n");
                for (uint o = 0; o < 16; o++)
                {
                    IDXGIOutput op;
                    if (ad.EnumOutputs(o, out op) != 0) break;
                    OUTPUT_DESC od; op.GetDesc(out od);
                    sb.Append("   " + o + " " + od.DeviceName + " " + (od.Right - od.Left) + "x" + (od.Bottom - od.Top) + " at " + od.Left + "," + od.Top + "\n");
                }
            }
        }
        catch (Exception e) { sb.Append(e.Message); }
        return sb.ToString();
    }
}

/** Windows' master volume for the default speakers, through Core Audio. */
/**
 * What the laptop's speakers are playing, as it plays: WASAPI's loopback capture of the default
 * output, in the device's own mix format (32-bit float, usually 48 kHz stereo). No driver and no
 * "Stereo Mix" needed. Read() hands on the bytes as they come; silence comes as zeros.
 */
public class Loopback : IDisposable
{
    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice
    {
        int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    }

    [Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient
    {
        int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
        int GetBufferSize(out uint frames);
        int GetStreamLatency(out long latency);
        int GetCurrentPadding(out uint padding);
        int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
        int GetMixFormat(out IntPtr format);
        int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        int Start();
        int Stop();
        int Reset();
        int SetEventHandle(IntPtr handle);
        int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient
    {
        int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        int ReleaseBuffer(uint frames);
        int GetNextPacketSize(out uint frames);
    }

    readonly IAudioClient client;
    readonly IAudioCaptureClient capture;
    public readonly int Rate, Channels, BlockAlign;
    public readonly bool Float;

    public Loopback()
    {
        // Made from its class id, not "new": MasterVolume declares the same COM class, and .NET hands
        // back its wrapper type, which then cannot be cast to this one.
        var e = (IMMDeviceEnumerator)Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")));
        IMMDevice dev;
        Marshal.ThrowExceptionForHR(e.GetDefaultAudioEndpoint(0, 1, out dev)); // render, multimedia
        Guid iid = typeof(IAudioClient).GUID;
        object o;
        Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23, IntPtr.Zero, out o));
        client = (IAudioClient)o;
        IntPtr fmt;
        Marshal.ThrowExceptionForHR(client.GetMixFormat(out fmt));
        int tag = Marshal.ReadInt16(fmt, 0) & 0xFFFF;
        Channels = Marshal.ReadInt16(fmt, 2);
        Rate = Marshal.ReadInt32(fmt, 4);
        BlockAlign = Marshal.ReadInt16(fmt, 12);
        int bits = Marshal.ReadInt16(fmt, 14);
        // WAVE_FORMAT_IEEE_FLOAT, or EXTENSIBLE (which the mix format is) at 32 bits: float.
        Float = tag == 3 || (tag == 0xFFFE && bits == 32);
        // Shared, loopback, a 200 ms buffer.
        Marshal.ThrowExceptionForHR(client.Initialize(0, 0x00020000, 2000000, 0, fmt, IntPtr.Zero));
        Marshal.FreeCoTaskMem(fmt);
        Guid cid = typeof(IAudioCaptureClient).GUID;
        object c;
        Marshal.ThrowExceptionForHR(client.GetService(ref cid, out c));
        capture = (IAudioCaptureClient)c;
        Marshal.ThrowExceptionForHR(client.Start());
    }

    /** What has been played since the last call (empty when nothing has). */
    public byte[] Read()
    {
        var all = new MemoryStream();
        uint next;
        while (capture.GetNextPacketSize(out next) == 0 && next > 0)
        {
            IntPtr data; uint frames, flags; ulong pos, qpc;
            if (capture.GetBuffer(out data, out frames, out flags, out pos, out qpc) != 0) break;
            int n = (int)frames * BlockAlign;
            byte[] b = new byte[n];
            if ((flags & 2) == 0) Marshal.Copy(data, b, 0, n); // AUDCLNT_BUFFERFLAGS_SILENT: zeros
            all.Write(b, 0, n);
            capture.ReleaseBuffer(frames);
        }
        return all.ToArray();
    }

    public void Dispose() { try { client.Stop(); } catch { } }
}

public static class MasterVolume
{
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumerator { }

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice
    {
        int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    }

    [Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioEndpointVolume
    {
        int RegisterControlChangeNotify(IntPtr notify);
        int UnregisterControlChangeNotify(IntPtr notify);
        int GetChannelCount(out int count);
        int SetMasterVolumeLevel(float db, ref Guid context);
        int SetMasterVolumeLevelScalar(float level, ref Guid context);
        int GetMasterVolumeLevel(out float db);
        int GetMasterVolumeLevelScalar(out float level);
        int SetChannelVolumeLevel(uint channel, float db, ref Guid context);
        int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid context);
        int GetChannelVolumeLevel(uint channel, out float db);
        int GetChannelVolumeLevelScalar(uint channel, out float level);
        int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
        int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }

    /** The default speakers as they are now: a different device may be plugged in any time. */
    static IAudioEndpointVolume Endpoint()
    {
        var e = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        IMMDevice dev;
        Marshal.ThrowExceptionForHR(e.GetDefaultAudioEndpoint(0, 1, out dev)); // render, multimedia
        Guid iid = typeof(IAudioEndpointVolume).GUID;
        object o;
        Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23, IntPtr.Zero, out o)); // CLSCTX_ALL
        return (IAudioEndpointVolume)o;
    }

    public static float Get() { float v; Endpoint().GetMasterVolumeLevelScalar(out v); return v; }

    public static void Set(float level)
    {
        Guid g = Guid.Empty;
        var ep = Endpoint();
        ep.SetMasterVolumeLevelScalar(Math.Max(0f, Math.Min(1f, level)), ref g);
        // Turning it up means wanting to hear it.
        if (level > 0) { bool m; ep.GetMute(out m); if (m) ep.SetMute(false, ref g); }
    }

    public static bool Muted() { bool m; Endpoint().GetMute(out m); return m; }

    public static void SetMute(bool mute) { Guid g = Guid.Empty; Endpoint().SetMute(mute, ref g); }
}

'@

Add-Type -TypeDefinition $source -Language CSharp -ReferencedAssemblies System.Windows.Forms, System.Drawing, System.IO.Compression, System.IO.Compression.FileSystem, System.Numerics
[BlazeItPc]::Run($f)
