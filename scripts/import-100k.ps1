# Imports 100,000 product rows (a few deliberately bad) and prints the time and the error report size.
# Needs core running on localhost:8080 with BILLING_MOCK=true (the default), because >200 products needs Pro.
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080/api/v1'
$rows = 100000

$email = "import-$([guid]::NewGuid().ToString('N').Substring(0,8))@test.com"
$s = Invoke-RestMethod -Method Post -Uri "$api/auth/signup" -ContentType 'application/json' `
  -Body (@{ email = $email; password = 'Passw0rd!'; fullName = 'Import'; businessName = 'Big Import Store' } | ConvertTo-Json)
$h = @{ Authorization = "Bearer $($s.accessToken)" }

# Upgrade to Pro (mock payment) so the 200-product Free limit does not apply
Invoke-RestMethod -Method Post -Uri "$api/billing/upgrade" -Headers $h | Out-Null
Invoke-RestMethod -Method Post -Uri "$api/billing/dev/simulate" -Headers $h -ContentType 'application/json' -Body '{"event":"subscription.authenticated"}' | Out-Null
$shop = (Invoke-RestMethod -Method Post -Uri "$api/shops" -Headers $h -ContentType 'application/json' -Body '{"name":"Main","template":"GROCERY"}').id

$path = Join-Path $env:TEMP 'stocksafe-100k.csv'
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('barcode,name,unit,gst_rate,reorder_level,brand')
for ($i = 1; $i -le $rows; $i++) {
  $gst = if ($i % 20000 -eq 0) { 99 } else { 12 }                 # rows 20000, 40000 ... have a bad GST rate
  $bc  = if ($i % 25000 -eq 0) { 'BC1' } else { "BC$i" }          # rows 25000, 50000, 75000 repeat the barcode of row 1
  [void]$sb.AppendLine("$bc,Item $i,pcs,$gst,5,Brand$($i % 50)")
}
[IO.File]::WriteAllText($path, $sb.ToString())
Write-Host "CSV written: $path ($([math]::Round((Get-Item $path).Length / 1MB, 1)) MB)"

$sw = [Diagnostics.Stopwatch]::StartNew()
$job = (Invoke-RestMethod -Method Post -Uri "$api/shops/$shop/imports/products" -Headers $h -ContentType 'text/csv' -InFile $path).jobId
do {
  Start-Sleep 2
  $j = Invoke-RestMethod -Uri "$api/imports/$job" -Headers $h
  Write-Host ("  {0} {1}/{2}  saved {3}  errors {4}" -f $j.status, $j.processed_rows, $j.total_rows, $j.success_rows, $j.error_rows)
} while ($j.status -eq 'RUNNING')
$sw.Stop()

$err = Invoke-RestMethod -Uri "$api/imports/$job/errors.csv" -Headers $h
Write-Host "`nStatus: $($j.status)"
Write-Host "Rows: $($j.total_rows)  saved: $($j.success_rows)  with errors: $($j.error_rows)"
Write-Host ("Time: {0:N1} seconds  ({1:N0} rows/second)" -f $sw.Elapsed.TotalSeconds, ($rows / $sw.Elapsed.TotalSeconds))
Write-Host "First error report lines:"
($err -split "`n")[0..4] | ForEach-Object { Write-Host "  $_" }
