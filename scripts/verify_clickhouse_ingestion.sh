#!/bin/bash
COUNT=$(curl -s "http://localhost:8123/?query=SELECT+count()+FROM+wiki_edits")

if [[ "$COUNT" -gt 0 ]]; then
    echo "Success: $COUNT records found in ClickHouse."
    exit 0
else
    echo "Error: No records found in ClickHouse."
    exit 1
fi
