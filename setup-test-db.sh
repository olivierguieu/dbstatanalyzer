#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# setup-test-db.sh
# Starts the DataGrip Sybase ASE 16 Docker image, waits for it to be ready,
# then creates a 'sales' table with skewed data and statistics on all columns.
#
# Run:   ./setup-test-db.sh
# ---------------------------------------------------------------------------
set -euo pipefail

CONTAINER="sybase16"
IMAGE="datagrip/sybase:16.0"
SA_PASS="myPassword"
ASE_SERVER="MYSYBASE"
MAX_WAIT=360    # seconds — allow extra time on Apple Silicon (Rosetta)

# ── helpers ─────────────────────────────────────────────────────────────────

die() { echo "ERROR: $*" >&2; exit 1; }

isql() {
    docker exec -i -e SYBASE=/opt/sybase "$CONTAINER" /opt/sybase/OCS-16_0/bin/isql \
        -S "$ASE_SERVER" -U sa -P "$SA_PASS" -w 200 "$@"
}

# Returns 0 if the given host port is free, 1 if already bound.
port_free() {
    ! lsof -i TCP:"$1" -sTCP:LISTEN > /dev/null 2>&1
}

# Finds the first free port starting from $1 (default 5010).
# Port 5000 is reserved by macOS AirPlay Receiver on Monterey+, so we
# start from 5010 to avoid the most common conflict.
find_free_port() {
    local p=${1:-5010}
    while ! port_free "$p"; do
        p=$((p + 1))
    done
    echo "$p"
}

# ── 1. Docker sanity check ───────────────────────────────────────────────────

docker info > /dev/null 2>&1 || die "Docker is not running — please start Docker Desktop first."

# ── 2. Start / reuse container ───────────────────────────────────────────────

HOST_PORT=""

if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    if docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
        echo "Container '$CONTAINER' is already running — skipping start."
    else
        echo "Starting existing container '$CONTAINER'..."
        docker start "$CONTAINER"
    fi
    # Retrieve whichever host port was mapped when the container was created
    HOST_PORT=$(docker port "$CONTAINER" 5000 2>/dev/null | head -1 | cut -d: -f2)
    [ -n "$HOST_PORT" ] || die "Could not determine host port for '$CONTAINER'."
else
    HOST_PORT=$(find_free_port 5010)
    if [ "$HOST_PORT" != "5000" ]; then
        echo "Note: host port 5000 is in use (macOS AirPlay?). Using port $HOST_PORT instead."
    fi
    echo "Pulling and starting $IMAGE on port $HOST_PORT  (first pull is ~3 GB)..."
    docker run -d -t -p "${HOST_PORT}:5000" --name "$CONTAINER" "$IMAGE"
fi

# ── 3. Wait for ASE to be ready ──────────────────────────────────────────────
#
# The entrypoint prints "SYBASE INITIALIZED" once the server is up AND the
# testdb database has been created.  We also try a direct isql connection as
# a fallback in case the log message format ever changes.

echo "Waiting for Sybase ASE (up to ${MAX_WAIT}s — Apple Silicon/Rosetta can be slow)..."
elapsed=0
while true; do
    # Primary check: entrypoint's final ready marker
    if docker logs "$CONTAINER" 2>&1 | grep -q "SYBASE INITIALIZED"; then
        echo "  -> log marker found (${elapsed}s)"
        break
    fi

    # Fallback after 60 s: try a real connection — handles image variants
    if [ "$elapsed" -ge 60 ]; then
        if timeout 8 docker exec -i -e SYBASE=/opt/sybase "$CONTAINER" \
               /opt/sybase/OCS-16_0/bin/isql \
               -S "$ASE_SERVER" -U sa -P "$SA_PASS" -l 5 \
               > /dev/null 2>&1 <<< $'select 1\ngo\n'; then
            echo "  -> connection succeeded (${elapsed}s)"
            break
        fi
    fi

    sleep 4
    elapsed=$((elapsed + 4))

    # Print last log line every 30 s so the user can see progress
    if [ $((elapsed % 30)) -eq 0 ]; then
        last=$(docker logs "$CONTAINER" 2>&1 | tail -1)
        echo "  [${elapsed}s] $last"
    fi

    if [ "$elapsed" -ge "$MAX_WAIT" ]; then
        echo "Last 30 log lines:"
        docker logs "$CONTAINER" 2>&1 | tail -30
        die "ASE did not start after ${MAX_WAIT}s. Check Docker memory (needs ≥2 GB) and that Rosetta is enabled for amd64 images."
    fi
done
sleep 3   # brief settle after the ready marker

# ── 4. Create table, indexes, and statistics ─────────────────────────────────
# Use -D to start isql directly in testdb — avoids the USE/go timing issue
# where Sybase isql may silently ignore a USE at the top of a piped batch.

echo "Setting up test data in 'testdb'..."

isql -D testdb << 'ENDSQL'

-- Belt-and-suspenders: switch db even though -D already sets it
use testdb
go

select "Active database" = db_name()
go

-- Idempotent: drop if re-running the script
if object_id('sales') is not null
    drop table sales
go

create table sales (
    id       int            not null,
    region   char(10)       not null,
    amount   numeric(12,2)  not null,
    saledate datetime       not null
)
go

-- Verify table was created before inserting
if object_id('sales') is null
begin
    print 'ERROR: sales table was not created — aborting'
    return
end
go

-- Deliberately skewed distributions for interesting histograms:
--   region   NORTH 50%, SOUTH 25%, EAST 15%, WEST 10%
--   amount   three price bands: cheap (0-99) / mid (0-499) / high (0-4998)
--   saledate spread over 2 years
declare @i int
select @i = 1
while @i <= 2000
begin
    insert into sales values (
        @i,
        case
            when @i % 10 in (0,1,2,3,4) then 'NORTH     '
            when @i % 10 in (5,6,7)     then 'SOUTH     '
            when @i % 10 = 8            then 'EAST      '
            else                             'WEST      '
        end,
        case
            when @i % 3 = 0 then convert(numeric(12,2), (@i % 50)  * 1.99)
            when @i % 3 = 1 then convert(numeric(12,2), (@i % 100) * 4.99)
            else                  convert(numeric(12,2), (@i % 200) * 24.99)
        end,
        dateadd(day, @i % 730, '2022-01-01')
    )
    select @i = @i + 1
end
go

-- Checkpoint to prevent transaction log filling up on a small default log device
checkpoint
go

select "Rows inserted" = count(*) from sales
go

-- Indexes drive automatic stats; one per column for maximum coverage
create unique index ix_id      on sales(id)
go
create        index ix_region  on sales(region)
go
create        index ix_amount  on sales(amount)
go
create        index ix_date    on sales(saledate)
go

update statistics sales
go

-- Final confirmation
select "Stat entries" = count(*)
from sysstatistics
where id = object_id('sales')
go
ENDSQL

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Setup complete.  Connect the tool with:"
echo "   Host:     localhost"
echo "   Port:     $HOST_PORT"
echo "   Database: testdb"
echo "   User:     tester     Password: guest1234"
echo "   Table:    sales"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
