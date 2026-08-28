#!/bin/bash
# Szybki test Gemini API - waliduje że provider działa zanim wgramy na telefon.
# Użycie: ./test_gemini.sh <API_KEY>
# Opcjonalnie: GEMINI_API_KEY env var

set -e

API_KEY="${1:-$GEMINI_API_KEY}"

if [ -z "$API_KEY" ]; then
  echo "Użycie: $0 <API_KEY>"
  echo "Lub ustaw: export GEMINI_API_KEY=..."
  exit 1
fi

# Minimalistyczny test - wyślij pytanie tekstowe
RESPONSE=$(curl -s -X POST \
  "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "contents": [{
      "parts": [{
        "text": "Odpowiedz krótko po polsku: jak się masz?"
      }]
    }]
  }')

# Wyciągnij tekst
TEXT=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if 'candidates' in data and len(data['candidates']) > 0:
        parts = data['candidates'][0]['content']['parts']
        if len(parts) > 0:
            print(parts[0]['text'])
        else:
            print('No parts')
    else:
        print('ERROR:', json.dumps(data, indent=2)[:500])
except Exception as e:
    print('Parse error:', e)
    print(sys.stdin.read()[:500])
")

echo "Odpowiedź Gemini:"
echo "=================="
echo "$TEXT"
echo "=================="

if echo "$TEXT" | grep -q "ERROR"; then
  exit 1
fi
