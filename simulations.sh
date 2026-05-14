#!/bin/bash

# 1. Load variables from .env file (ignoring comments)
if [ -f .env ]; then
    export $(grep -v '^#' .env | xargs)
else
    echo "Error: .env file not found!"
    exit 1
fi

JAR_PATH="gatling-llm-simulations-0.1.0-SNAPSHOT.jar"
SIMULATION_CLASS="simulations.BasicLLMUsersSimulation"

# 2. Define our test cases (name, basic, standard, pro)
# Format: "name:basic:standard:pro"
cases=(
    "baseline:3:5:7"
    "moderate:4:6:8"
    "excessive:7:10:14"
)

# 3. Iterate through cases
for case in "${cases[@]}"; do
    # Split the string by colon
    IFS=":" read -r NAME BASIC STANDARD PRO <<< "$case"
    
    RUN_ID="${NAME}-$(date +%Y%m%d%H%M%S)"
    echo "-------------------------------------------------------"
    echo "Starting Simulation: $NAME (RunID: $RUN_ID)"
    echo "Settings: Basic=$BASIC, Standard=$STANDARD, Pro=$PRO"
    echo "-------------------------------------------------------"

    # 4. Execute Java
    # We pass all .env vars AND the specific case overrides as -D properties
    java -Dgatling.core.checkVersion=false \
         -DENDPOINT_PATH="$ENDPOINT_PATH" \
         -DMODELS_ENDPOINT="$MODELS_ENDPOINT" \
         -DCAPTURE_RESPONSES="$CAPTURE_RESPONSES" \
         -DSIMULATION_MINUTES="$SIMULATION_MINUTES" \
         -DTOTAL_USERS="$TOTAL_USERS" \
         -DUNITS_PER_REQUEST="$UNITS_PER_REQUEST" \
         -DBASIC_UNITS_PER_MINUTE="$BASIC" \
         -DSTANDARD_UNITS_PER_MINUTE="$STANDARD" \
         -DPRO_UNITS_PER_MINUTE="$PRO" \
         -jar "$JAR_PATH" \
         -s "$SIMULATION_CLASS" \
         -rn "$RUN_ID"

    echo "Finished $NAME. Results are in the results folder."
done