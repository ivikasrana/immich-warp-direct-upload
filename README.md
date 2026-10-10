# Uploading big files to Immich through Cloudflare Zero Trust (WARP), without the 100 MB limit

If you publish Immich through a Cloudflare Tunnel, every upload goes through Cloudflare's proxy. On the Free and Pro plans that proxy rejects any request body over 100 MB with `HTTP 413`, so large videos from the phone fail.

This repo shows a setup that avoids the limit **without changing the URL in the Immich app** (still `https://photos.example.com`, no port number) and **without opening any port on your router**. When the phone has the Cloudflare One client (WARP) turned on, the hostname resolves to your server's LAN address and the traffic reaches your server through the tunnel as plain TCP, so Cloudflare's HTTP proxy is never involved.

It was built and tested for one case: **phone on mobile data, WARP on**. A 300 MB video upload went through. Other cases are covered under "Limits" below.

## How it works

```
Phone (WARP on)
  -> Gateway DNS override: photos.example.com -> 192.168.1.10
  -> WARP tunnel -> cloudflared on your server (private network route)
  -> nginx on 192.168.1.10:443 (TLS, Let's Encrypt cert)
  -> Immich on 127.0.0.1:2283
```

Without WARP, the same hostname resolves publicly to Cloudflare and works as before, with the 100 MB limit.

Why a TLS endpoint on the server is unavoidable: the app speaks HTTPS to port 443, and Immich itself only serves plain HTTP. Something on the server has to terminate TLS. Redirecting port 443 to Immich with iptables does not work, because the TLS handshake would land on a plain HTTP port. nginx is the smallest piece that does the job.

## What you need

- Immich running, reachable on the server (default port 2283).
- A Cloudflare Tunnel (`cloudflared`) on that server with:
  - WARP routing enabled, and
  - a private network route covering your LAN subnet, for example `192.168.1.0/24`.
- Cloudflare Zero Trust with the Cloudflare One client (WARP) enrolled on the phone.
- A domain on Cloudflare, plus an API token with `Zone -> DNS -> Edit` for that zone. This is used only to get the certificate through the DNS-01 challenge, so port 443 never has to be reachable from the internet.
- Docker on the server, with nothing else listening on 443. If you run OpenMediaVault, its web UI may use 443; move it first.

## Step 1: nginx and the certificate (on the server)

Copy the `nginx/` folder to the server, for example to `/opt/photos-proxy`, then replace `photos.example.com` with your hostname and the proxy port if yours is not 2283 in `conf.d/photos.conf`.

```
cd /opt/photos-proxy
cp cloudflare.ini.example cloudflare.ini
nano cloudflare.ini
chmod 600 cloudflare.ini
mkdir -p letsencrypt

docker run --rm \
  -v "$PWD/letsencrypt:/etc/letsencrypt" \
  -v "$PWD/cloudflare.ini:/cloudflare.ini:ro" \
  certbot/dns-cloudflare certonly \
  --dns-cloudflare \
  --dns-cloudflare-credentials /cloudflare.ini \
  --dns-cloudflare-propagation-seconds 30 \
  -d photos.example.com \
  --register-unsafely-without-email \
  --agree-tos \
  --non-interactive

docker compose up -d
```

Check it from the server:

```
curl -sS --resolve photos.example.com:443:127.0.0.1 https://photos.example.com/api/server/ping
```

You should get `{"res":"pong"}`.

Set up renewal, since the certificate lasts about 90 days:

```
chmod +x renew.sh
(crontab -l 2>/dev/null; echo '15 3 * * * /opt/photos-proxy/renew.sh') | crontab -
```

`client_max_body_size 0` in the nginx config matters. Without it nginx applies its own 1 MB default and uploads fail with 413 from nginx instead.

## Step 2: Cloudflare Zero Trust settings

1. **Gateway DNS policy** (Traffic controls -> Firewall policies -> DNS): create a policy with Host `is` `photos.example.com`, action **Override**, destination your server's LAN IP.
2. **Gateway network policy**: make sure traffic to your LAN subnet is allowed, and that any "deny private traffic" rule sits below the allow rule. Gateway evaluates policies top to bottom.
3. **Device profile -> Split tunnels**: the LAN subnet must go through WARP. In "Exclude" mode, remove your subnet's range if it is listed (for example `192.168.0.0/16`).
4. Traffic settings: leave TCP and UDP proxying on. TLS decryption is not needed.
5. **Do not also add a Local Domain Fallback entry for the same hostname.** In testing, having both the DNS override and a fallback entry caused the name to stop resolving on the phone ("address not found"). A fallback entry on its own also hung on mobile data. The DNS override alone worked.

## Step 3: check from the phone

`android-ipv4-info/` is a tiny debug app that lists the phone's networks (IPv4 only) and shows what your hostname resolves to, and whether TCP 443 is open on each address. Edit `TARGET_HOST` in `MainActivity.kt` to your hostname, then build:

```
cd android-ipv4-info
./gradlew assembleDebug
```

The APK ends up in `build/outputs/apk/debug/`. If Gradle complains about the SDK location, set `ANDROID_HOME` or create `local.properties` with `sdk.dir=...`.

With WARP on, mobile data, the app should show your LAN IP with `443: open`. A Cloudflare address (104.x, 172.67.x, 188.114.x) means the override is not being applied.

You can also watch nginx while uploading:

```
docker logs -f --tail 0 photos-nginx
```

A `POST /api/assets` line from the server's own address means the request came through the tunnel.

## Pitfalls we hit

- **A Let's Encrypt certificate does not prove you bypassed Cloudflare.** Cloudflare's own edge certificates can also be issued by Let's Encrypt. Compare the names on the certificate instead: yours covers only the one hostname, Cloudflare's covers the domain and a wildcard.
- **Android Wi-Fi DNS.** If the phone's Wi-Fi connection has a fixed DNS such as `1.1.1.1`, it ignores your router's local records. Turn off Private DNS and browser DoH when testing.
- **Gateway rule order.** An allow rule placed after a deny rule never matches.
- **Reading "Gateway DNS log" evidence.** Names handled by a Local Domain Fallback entry bypass Gateway DNS, so a missing log line there tells you nothing.

## Limits

- Large uploads are only uncapped while WARP is on. Without WARP, away from home, the name resolves to Cloudflare and the 100 MB limit applies.
- The Immich app uploads each asset as a single request, so the limit cannot be worked around by tuning the app.
- This keeps one more service (nginx) on your server, which you must keep updated.

## License

MIT, see `LICENSE`.
