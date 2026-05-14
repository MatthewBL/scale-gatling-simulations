#!/bin/bash
#SBATCH --job-name=gatling-llm-scale
#SBATCH --output=gatling_%j.log
#SBATCH --error=gatling_%j.err
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=4
#SBATCH --mem=8G
#SBATCH --time=04:00:00

# 1. Load Java module (adjust the name based on your cluster's available modules)
# Use 'module avail' to find the exact name, e.g., openjdk/11.0.2
module load openjdk/11

# 2. Load .env variables
if [ -f .env ]; then
    export $(grep -v '^#' .env | xargs)
else
    echo "Error: .env file not found!"
    exit 1
fi

JAR_PATH="gatling-llm-simulations-0.1.0-SNAPSHOT.jar"
SIMULATION_CLASS="simulations.BasicLLMUsersSimulation"

# 3. Define test cases
cases=(
    "baseline:3:5:7"
    "moderate:4:6:8"
    "excessive:7:10:14"
)

# 4. Sequential Execution Loop
for case in "${cases[@]}"; do
    IFS=":" read -r NAME BASIC STANDARD PRO <<< "$case"
    RUN_ID="${NAME}-$(date +%Y%m%d%H%M%S)"

    echo "======================================================="
    echo "SLURM Job ID: $SLURM_JOB_ID"
    echo "Running Case: $NAME"
    echo "======================================================="

    # We use -Xmx and -Xms to match the SBATCH --mem allocation
    # 6G is a safe heap size for an 8G memory reservation
    java -Xms2G -Xmx6G \
         -Dgatling.core.checkVersion=false \
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

done