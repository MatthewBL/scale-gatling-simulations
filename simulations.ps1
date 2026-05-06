$cases = @(
  @{ name = "baseline"; BASIC_UNITS_PER_MINUTE = 17; STANDARD_UNITS_PER_MINUTE = 26; PRO_UNITS_PER_MINUTE = 35 },
  @{ name = "moderate"; BASIC_UNITS_PER_MINUTE = 21; STANDARD_UNITS_PER_MINUTE = 31; PRO_UNITS_PER_MINUTE = 42 },
  @{ name = "excessive"; BASIC_UNITS_PER_MINUTE = 35; STANDARD_UNITS_PER_MINUTE = 52; PRO_UNITS_PER_MINUTE = 70 }
)

foreach ($c in $cases) {
  $runId = "{0}-{1:yyyyMMddHHmmss}" -f $c.name, (Get-Date)
  mvn gatling:test `
    "-Dgatling.runId=$runId" `
    "-DBASIC_UNITS_PER_MINUTE=$($c.BASIC_UNITS_PER_MINUTE)" `
    "-DSTANDARD_UNITS_PER_MINUTE=$($c.STANDARD_UNITS_PER_MINUTE)" `
    "-DPRO_UNITS_PER_MINUTE=$($c.PRO_UNITS_PER_MINUTE)"
}