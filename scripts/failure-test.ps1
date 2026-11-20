# Failure test: kill the alerts service while events are flowing.
# Proves: no event is lost, no event is applied twice, no alert is created twice.
# Run from the repo root in PowerShell. Needs Docker Desktop. Takes a few minutes (first build).
$ErrorActionPreference = 'Stop'
$N = 200          # number of stock receipts (each becomes one event and one alert)
$api = 'http://localhost:8080/api/v1'

function Sql($q) { (docker compose exec -T postgres psql -U stocksafe -d stocksafe -tA -c $q).Trim() }
function Step($m) { Write-Host "`n== $m" -ForegroundColor Cyan }

Step 'Start everything in containers'
docker compose --profile full up -d --build
for ($i = 0; $i -lt 90; $i++) {
  try { if ((Invoke-RestMethod http://localhost:8080/actuator/health).status -eq 'UP') { break } } catch { }
  Start-Sleep 2
}

Step 'Create a shop owner, a shop and a product'
$email = "failtest-$([guid]::NewGuid().ToString('N').Substring(0,8))@test.com"
$s = Invoke-RestMethod -Method Post -Uri "$api/auth/signup" -ContentType 'application/json' `
  -Body (@{ email = $email; password = 'Passw0rd!'; fullName = 'Fail Test'; businessName = 'Fail Test Medicals' } | ConvertTo-Json)
$h = @{ Authorization = "Bearer $($s.accessToken)" }
$tenant = $s.tenantId
$shop = (Invoke-RestMethod -Method Post -Uri "$api/shops" -Headers $h -ContentType 'application/json' -Body '{"name":"Main","template":"MEDICAL"}').id
$productId = (Invoke-RestMethod -Method Post -Uri "$api/shops/$shop/products" -Headers $h -ContentType 'application/json' -Body '{"name":"Paracetamol","barcode":"FT-1","reorderLevel":0}').id

Step 'Stop the alerts service'
docker compose stop alerts

Step "Create $N events while alerts is DOWN"
$exp = (Get-Date).AddDays(5).ToString('yyyy-MM-dd')
1..$N | ForEach-Object {
  $b = @{ productId = $productId; batchNo = "FT-$_"; expiryDate = $exp; cost = 5; mrp = 8; qty = 10 } | ConvertTo-Json
  Invoke-RestMethod -Method Post -Uri "$api/shops/$shop/stock/receive" -Headers $h -ContentType 'application/json' -Body $b | Out-Null
}

Step 'Start alerts, then KILL it hard while it is working, then start it again'
docker compose start alerts
Start-Sleep 6
docker compose kill alerts
Start-Sleep 2
docker compose start alerts

Step 'Wait until everything is delivered'
$expected = $N + 2            # N batch events + tenant.created + product.upserted
for ($i = 0; $i -lt 120; $i++) {
  $done = [int](Sql "select count(*) from processed_events where consumer='alerts' and event_id in (select id from outbox_events where tenant_id='$tenant')")
  $left = [int](Sql "select count(*) from outbox_events where tenant_id='$tenant' and published_at is null")
  Write-Host "  processed $done / $expected, not yet published $left"
  if ($done -ge $expected -and $left -eq 0) { break }
  Start-Sleep 3
}
Start-Sleep 5

Step 'Verify'
$events    = [int](Sql "select count(*) from outbox_events where tenant_id='$tenant'")
$processed = [int](Sql "select count(*) from processed_events where consumer='alerts' and event_id in (select id from outbox_events where tenant_id='$tenant')")
$snapshots = [int](Sql "select count(*) from batch_snapshots where tenant_id='$tenant'")
$alerts    = [int](Sql "select count(*) from alerts where tenant_id='$tenant' and type='NEAR_EXPIRY'")
$dupAlerts = [int](Sql "select count(*) from (select dedupe_key from alerts where tenant_id='$tenant' group by dedupe_key, status having count(*) > 1) d")
$emails    = [int](Sql "select count(*) from notifications where tenant_id='$tenant' and channel='EMAIL'")
$dlq       = (docker compose exec -T rabbitmq rabbitmqctl list_queues name messages | Select-String 'alerts.events.dlq').ToString()

$results = @(
  @('No event lost (processed = published)',   ($processed -eq $events),  "$processed of $events"),
  @("Every batch is in the alerts copy ($N)",   ($snapshots -eq $N),       "$snapshots"),
  @("One alert per batch ($N), none twice",     (($alerts -eq $N) -and ($dupAlerts -eq 0)), "$alerts alerts, $dupAlerts duplicated"),
  @("One email per alert ($N)",                 ($emails -eq $N),          "$emails")
)
$ok = $true
foreach ($r in $results) {
  $tag = if ($r[1]) { 'PASS' } else { 'FAIL'; $ok = $false }
  Write-Host ("{0}  {1}  ({2})" -f $tag, $r[0], $r[2])
}
Write-Host "Dead-letter queue: $dlq"
if ($ok) { Write-Host "`nRESULT: PASS. Killing the service lost nothing and duplicated nothing." -ForegroundColor Green }
else     { Write-Host "`nRESULT: FAIL" -ForegroundColor Red; exit 1 }
