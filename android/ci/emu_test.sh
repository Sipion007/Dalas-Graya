#!/usr/bin/env bash
# Tests réels de l'appli sur un téléphone Android virtuel (Android 14).
set -u
PKG=ci.dallasbouff.pro
TOK=citest-7f3a9c2e5b1d4e8fa6c0
SB=https://gdkcrazrejzpwzxbhpxp.supabase.co
KEY=sb_publishable_1cijZWLbC-Em1Q9o3UwpvA_VLmNtmwi
OUT=out
mkdir -p "$OUT"
: > "$OUT/results.txt"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/steps.txt"; }
pass() { echo "PASS $1" | tee -a "$OUT/results.txt"; }
fail() { echo "FAIL $1 :: ${2:-}" | tee -a "$OUT/results.txt"; }
rpc() { curl -s -m 20 -X POST "$SB/rest/v1/rpc/$1" -H "apikey: $KEY" -H "Content-Type: application/json" -d "$2"; }
item() { # k on kind rep title line
  rpc alarm_test_set "{\"p_token\":\"$TOK\",\"p_k\":\"$1\",\"p_on\":$2,\"p_kind\":\"$3\",\"p_rep\":$4,\"p_t\":\"$5\",\"p_l\":\"$6\"}" >/dev/null
}
clear_items() { for k in o1 o2 o3 o4 o5 m1; do item $k false order true x x; done; }
cnt() { adb logcat -d -s DBF:I 2>/dev/null | grep -c -- "$1"; }
# attend qu'un message apparaisse plus de fois qu'avant ; renvoie 0 si oui
waitfor() { local pat="$1" base="$2" t="$3" i=0
  while [ $i -lt "$t" ]; do [ "$(cnt "$pat")" -gt "$base" ] && return 0; sleep 1; i=$((i+1)); done; return 1; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }
running() { adb shell dumpsys activity services $PKG 2>/dev/null | grep -q "AlarmService"; }
fgs() { adb shell dumpsys activity services $PKG 2>/dev/null | grep -q "isForeground=true"; }
ack() { adb shell am broadcast -n $PKG/.TestReceiver -a ci.dallasbouff.pro.ACK >/dev/null 2>&1; }
tap_text() { # touche l'élément d'écran dont le texte commence par $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1 || return 1
  cp /tmp/ui.xml "$OUT/ui_$(date +%s).xml" 2>/dev/null
  local xy
  xy=$(python3 - "$1" <<'EOF'
import sys, re, xml.etree.ElementTree as ET
want = sys.argv[1].lower()
try:
    root = ET.parse('/tmp/ui.xml').getroot()
except Exception:
    sys.exit(1)
for n in root.iter('node'):
    t = (n.get('text') or n.get('content-desc') or '').lower()
    if t.startswith(want):
        a, b, c, d = map(int, re.findall(r'\d+', n.get('bounds')))
        print((a + c) // 2, (b + d) // 2); sys.exit(0)
sys.exit(1)
EOF
) || return 1
  adb shell input tap $xy
}

log "Préparation"
clear_items
adb logcat -c
adb install -r app-debug.apk > "$OUT/install.txt" 2>&1 && pass "installation de l'appli" || fail "installation" "$(cat $OUT/install.txt)"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set $PKG USE_FULL_SCREEN_INTENT allow >/dev/null 2>&1
adb shell settings put system screen_off_timeout 1800000

log "1. Premier lancement : écran de choix"
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 6; shot 00_choix
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
grep -q "Je suis restaurateur" /tmp/ui.xml && grep -q "Je suis livreur" /tmp/ui.xml && pass "écran de choix restaurateur / livreur" || fail "écran de choix" "boutons absents"

log "2. Espace restaurant et service d'alarme"
adb shell am start -n $PKG/.MainActivity --es dbf_test_token $TOK --es dbf_space restaurant >/dev/null; sleep 15; shot 01_espace_restaurant
running && fgs && pass "service d'alarme démarré au premier plan" || fail "service d'alarme" "$(adb shell dumpsys activity services $PKG | head -20)"
[ "$(cnt 'POLL ok')" -gt 0 ] && pass "le service interroge le serveur" || fail "interrogation serveur" "$(adb logcat -d -s DBF:* | tail -20)"
[ "$(cnt 'PAGE finished https://dalas-graya.vercel.app/dashboard.html')" -gt 0 ] && pass "la page restaurant s'affiche dans l'appli" || fail "page restaurant" "$(adb logcat -d -s DBF:* | grep PAGE)"
c0=$(cnt 'RING start'); sleep 25
[ "$(cnt 'RING start')" -eq "$c0" ] && pass "aucune alarme sans commande" || fail "alarme sans commande"

log "3. Appli fermée : nouvelle commande"
adb shell input keyevent KEYCODE_HOME; sleep 3
c0=$(cnt 'RING start'); t0=$(date +%s)
item o1 true order true "NOUVELLE COMMANDE" "Awa Test · 6 500 F · Allergie : arachides"
if waitfor 'RING start' $c0 40; then pass "sonne appli fermée ($(( $(date +%s) - t0 )) s après la commande)"; else fail "sonne appli fermée" "$(adb logcat -d -s DBF:* | tail -15)"; fi
sleep 3; shot 02_alarme_appli_fermee
adb shell dumpsys audio > "$OUT/dumpsys_audio.txt" 2>&1
grep -q "USAGE_ALARM" "$OUT/dumpsys_audio.txt" && pass "son joué sur le canal réveil (passe le mode silencieux)" || fail "canal réveil" "USAGE_ALARM absent"
adb shell dumpsys notification --noredact > "$OUT/dumpsys_notif.txt" 2>&1
grep -q "NOUVELLE COMMANDE" "$OUT/dumpsys_notif.txt" && pass "notification d'alarme affichée" || fail "notification d'alarme"

log "4. J'ai vu depuis la notification, puis retour de l'alarme"
adb shell cmd statusbar expand-notifications; sleep 3; shot 03_volet_notifications
c0=$(cnt 'RING ack')
if tap_text "j'ai vu"; then waitfor 'RING ack' $c0 10 && pass "bouton J'ai vu de la notification" || fail "bouton J'ai vu (notification)" "pas d'arrêt"; else fail "bouton J'ai vu introuvable dans la notification" ""; ack; fi
adb shell cmd statusbar collapse; t0=$(date +%s); c0=$(cnt 'RING start')
sleep 60
[ "$(cnt 'RING start')" -eq "$c0" ] && pass "silence pendant la pause" || fail "silence pendant la pause"
if waitfor 'RING start' $c0 60; then pass "l'alarme revient après la pause ($(( $(date +%s) - t0 )) s)"; else fail "retour de l'alarme après 1 min 30"; fi

log "5. Commande acceptée : l'alarme s'arrête"
c0=$(cnt 'RING stop (nothing pending)')
item o1 false order true x x
waitfor 'RING stop (nothing pending)' $c0 35 && pass "acceptation : l'alarme s'arrête toute seule" || fail "arrêt après acceptation"

log "6. Message : sonne une fois, J'ai vu définitif"
c0=$(cnt 'RING start')
item m1 true message false "NOUVEAU MESSAGE" "Koffi : je suis devant le portail bleu"
waitfor 'RING start' $c0 40 && pass "message : alarme" || fail "message : alarme"
ack; sleep 2; c0=$(cnt 'RING start'); sleep 110
[ "$(cnt 'RING start')" -eq "$c0" ] && pass "message vu : ne sonne plus" || fail "message vu : sonne encore"
item m1 false message false x x

log "7. Appli ouverte : c'est l'appli qui gère, pas de double alarme"
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 4
c0=$(cnt 'RING start')
item o2 true order true "NOUVELLE COMMANDE" "Koffi · 12 000 F"
sleep 40
[ "$(cnt 'RING start')" -eq "$c0" ] && pass "appli à l'écran : pas d'alarme du service" || fail "appli à l'écran : double alarme"
adb shell input keyevent KEYCODE_HOME; t0=$(date +%s)
waitfor 'RING start' $c0 130 && pass "appli quittée sans accepter : sonne ($(( $(date +%s) - t0 )) s après)" || fail "appli quittée : ne sonne pas"
ack; item o2 false order true x x; sleep 25

log "8. Écran éteint et mode veille profonde (Doze)"
adb shell input keyevent KEYCODE_SLEEP; sleep 2
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle > "$OUT/doze.txt" 2>&1
c0=$(cnt 'RING start'); t0=$(date +%s)
item o3 true order true "NOUVELLE COMMANDE" "Test écran éteint · 4 000 F"
if waitfor 'RING start' $c0 60; then pass "écran éteint + veille profonde : sonne ($(( $(date +%s) - t0 )) s)"; else fail "écran éteint + veille profonde" "$(cat $OUT/doze.txt)"; fi
sleep 5; shot 04_ecran_verrouille
adb shell dumpsys activity activities > "$OUT/activities.txt" 2>&1
grep -q "AlarmActivity" "$OUT/activities.txt" && pass "écran d'alarme affiché par-dessus le verrouillage" || fail "écran d'alarme plein écran"
adb shell dumpsys power | grep -i "mWakefulness=" > "$OUT/power.txt"
grep -qi "Awake" "$OUT/power.txt" && pass "l'écran s'allume tout seul" || fail "écran allumé" "$(cat $OUT/power.txt)"
c0=$(cnt 'RING ack')
if tap_text "j'ai vu"; then waitfor 'RING ack' $c0 10 && pass "bouton J'ai vu de l'écran d'alarme" || fail "bouton J'ai vu (écran)"; else fail "bouton J'ai vu introuvable (écran)"; ack; fi
adb shell dumpsys deviceidle unforce >/dev/null; adb shell dumpsys battery reset
item o3 false order true x x; sleep 25

log "9. Livreur : position GPS envoyée en arrière-plan"
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
adb emu geo fix -6.4502 6.8770 >/dev/null 2>&1
c0=$(cnt 'LOCATION on')
adb shell am start -n $PKG/.MainActivity --es dbf_test_token $TOK --es dbf_test_role driver --es dbf_space driver >/dev/null; sleep 12
[ "$(cnt 'PAGE finished https://dalas-graya.vercel.app/driver.html')" -gt 0 ] && pass "la page livreur s'affiche dans l'appli" || fail "page livreur"
shot 05_espace_livreur
adb shell input keyevent KEYCODE_HOME; sleep 3
waitfor 'LOCATION on' $c0 15 && pass "suivi GPS du livreur actif appli fermée" || fail "suivi GPS livreur" "$(adb logcat -d -s DBF:* | grep -i 'location\|FGS' | tail -5)"
adb shell dumpsys activity services $PKG > "$OUT/services_driver.txt" 2>&1
grep -q "isForeground=true" "$OUT/services_driver.txt" && pass "service livreur au premier plan (type position)" || fail "service livreur"
grep -qi "FGS location refused" "$OUT/logcat_tmp.txt" 2>/dev/null; adb logcat -d -s DBF:* | grep -q "FGS location refused" && fail "type position refusé" || pass "type position accepté par Android 14"
adb emu geo fix -6.4510 6.8790 >/dev/null 2>&1; sleep 3
adb shell am start -n $PKG/.MainActivity --es dbf_test_token $TOK --es dbf_test_role test --es dbf_space restaurant >/dev/null; sleep 6
adb shell input keyevent KEYCODE_HOME; sleep 2

log "10. Redémarrage du téléphone"
adb reboot
adb wait-for-device
for i in $(seq 1 120); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 2; done
sleep 25
running && pass "service relancé après redémarrage" || fail "service relancé après redémarrage" "$(adb logcat -d -s DBF:* | tail -10)"
c0=$(cnt 'RING start')
item o4 true order true "NOUVELLE COMMANDE" "Après redémarrage · 3 000 F"
waitfor 'RING start' $c0 60 && pass "sonne après redémarrage sans ouvrir l'appli" || fail "sonne après redémarrage"
ack; item o4 false order true x x

log "Fin"
clear_items
adb logcat -d > "$OUT/logcat_full.txt" 2>&1
adb logcat -d -s DBF:* > "$OUT/logcat_dbf.txt" 2>&1
echo "TOTAL $(grep -c . $OUT/results.txt) tests, $(grep -c '^FAIL' $OUT/results.txt) échec(s)" | tee -a "$OUT/results.txt"
exit 0
