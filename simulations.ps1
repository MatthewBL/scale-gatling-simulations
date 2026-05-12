$cases = @(
  #@{ name = "baseline"; BASIC_UNITS_PER_MINUTE = 3; STANDARD_UNITS_PER_MINUTE = 5; PRO_UNITS_PER_MINUTE = 7 },
  @{ name = "moderate"; BASIC_UNITS_PER_MINUTE = 4; STANDARD_UNITS_PER_MINUTE = 6; PRO_UNITS_PER_MINUTE = 8 },
  @{ name = "excessive"; BASIC_UNITS_PER_MINUTE = 7; STANDARD_UNITS_PER_MINUTE = 10; PRO_UNITS_PER_MINUTE = 14 }
)

foreach ($c in $cases) {
  $runId = "{0}-{1:yyyyMMddHHmmss}" -f $c.name, (Get-Date)
  mvn gatling:test `
    "-Dgatling.runId=$runId" `
    "-DBASIC_UNITS_PER_MINUTE=$($c.BASIC_UNITS_PER_MINUTE)" `
    "-DSTANDARD_UNITS_PER_MINUTE=$($c.STANDARD_UNITS_PER_MINUTE)" `
    "-DPRO_UNITS_PER_MINUTE=$($c.PRO_UNITS_PER_MINUTE)"
}