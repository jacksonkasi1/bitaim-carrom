/**
 * BitAim v3.1 — Carrom Pool Aim Assistant
 *
 * v3.1 additions:
 *  - Snap-to-Coin mode: tap any detected coin while snap is ON and the aim line
 *    automatically computes the perfect angle to pocket that coin. A lime dotted
 *    line from coin → pocket confirms the intended path.
 *
 * v3.0 features:
 *  - Striker is moveable — drag the gold-ringed striker to reposition.
 *  - Auto-detect board size from wooden frame color.
 *  - Single clean cyan aim line (+ one orange deflection line when hitting a coin).
 *  - Multi-threshold detection for stronger piece recognition.
 */

import React, {useState, useEffect, useCallback} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Alert,
  Switch,
  ScrollView,
  Platform,
  StatusBar,
  NativeModules,
  Linking,
  PermissionsAndroid,
} from 'react-native';
import Slider from '@react-native-community/slider';

const {OverlayModule} = NativeModules;

type ShotMode = 'ALL' | 'DIRECT' | 'AI' | 'GOLDEN' | 'LUCKY';

interface MarginSettings {
  d2X: number; d2Y: number;
  e2X: number; e2Y: number;
  insideX: number; insideY: number;
}

const SHOT_MODES: {mode: ShotMode; label: string; desc: string}[] = [
  {mode: 'ALL',    label: 'All Lines', desc: 'Striker + best coin shot'},
  {mode: 'DIRECT', label: 'Direct',    desc: 'Striker direct line only'},
  {mode: 'AI',     label: 'AI Aim',    desc: 'Best AI-ranked trajectory'},
  {mode: 'GOLDEN', label: 'Golden',    desc: 'Up to one cushion bounce'},
  {mode: 'LUCKY',  label: 'Lucky',     desc: 'Up to two cushion bounces'},
];

export default function App() {
  const [hasOverlay, setHasOverlay]             = useState(false);
  const [overlayActive, setOverlayActive]       = useState(false);
  const [autoDetect, setAutoDetect]             = useState(false);
  const [autoPlay, setAutoPlay] = useState(false);
  const [debugMode, setDebugMode] = useState(false);
  const [recStats, setRecStats] = useState('');
  const [pcLink, setPcLink] = useState(false);
  const [pcState, setPcState] = useState('');
  const refreshPc = async () => {try {const [on, st] = String(await OverlayModule.getPcLink()).split('|'); setPcLink(on === 'on'); setPcState(st);} catch {}};
  const refreshRecStats = async () => {
    try {
      const [shots, pics, bytes, matches] = String(await OverlayModule.getRecordingStats()).split('|').map(Number);
      setRecStats(`${matches || 0} matches • ${shots} shots • ${pics} pictures • ${(bytes / 1048576).toFixed(1)} MB`);
    } catch {}
  };
  const [autoPull, setAutoPull] = useState(12);
  const [allColours, setAllColours] = useState(false);
  const [targetColourKey,setTargetColourKey] = useState(0);
  const [strikerMoveable, setStrikerMoveable]   = useState(true);
  const [snapMode, setSnapMode]                 = useState(false);   // v3.1
  const [selectedMode, setSelectedMode]         = useState<ShotMode>('ALL');
  const [sensitivity, setSensitivity]           = useState(1.0);
  const [detectThreshold, setDetectThreshold]   = useState(18);
  const [margin, setMargin] = useState<MarginSettings>({
    d2X: 0, d2Y: 0, e2X: 0, e2Y: 0, insideX: 0, insideY: 0,
  });
  const [activeMarginTab, setActiveMarginTab] =
    useState<'D2' | 'E2' | 'INSIDE'>('D2');

  useEffect(() => {
    // Android 13+: ask for notification permission so the "BitAim running"
    // notification of the overlay / capture services can be shown.
    if (Platform.OS === 'android' && Number(Platform.Version) >= 33) {
      PermissionsAndroid.request(
        'android.permission.POST_NOTIFICATIONS' as any,
      ).catch(() => {});
    }
    refreshStatus();
    const t = setInterval(refreshStatus, 2000);
    return () => clearInterval(t);
  }, []);

  const refreshStatus = useCallback(async () => {
    try {setTargetColourKey(await OverlayModule.getTargetColourKey());} catch {}
    try {setAllColours(await OverlayModule.getAllColours());} catch {}
    try {setAutoPlay(await OverlayModule.isAutoPlayEnabled());} catch {}
    try {setDebugMode(await OverlayModule.getDebugMode());} catch {}
    refreshRecStats();
    refreshPc();
    try {
      const can = await OverlayModule.canDrawOverlays();
      setHasOverlay(can);
    } catch { setHasOverlay(false); }
    try {
      const active = await OverlayModule.isAutoDetectActive();
      setAutoDetect(active);
    } catch {}
    try {
      // Keeps the switch correct if the overlay was stopped from the notification
      const ov = await OverlayModule.isOverlayActive();
      setOverlayActive(ov);
    } catch {}
  }, []);

  const requestOverlay = useCallback(() => {
    try {
      OverlayModule.requestOverlayPermission();
      setTimeout(refreshStatus, 1500);
    } catch {
      Alert.alert('Permission Needed',
        'Please grant "Display over other apps" in Settings.',
        [{text: 'Open Settings', onPress: () => Linking.openSettings()}]);
    }
  }, [refreshStatus]);

  const toggleOverlay = useCallback(async () => {
    if (!hasOverlay) { requestOverlay(); return; }
    try {
      if (overlayActive) {
        await OverlayModule.stopOverlay();
        setOverlayActive(false);
        setAutoDetect(false);
      } else {
        await OverlayModule.startOverlay();
        setOverlayActive(true);
        try { OverlayModule.setStrikerMoveable(strikerMoveable); } catch {}
        try { OverlayModule.setSnapMode(snapMode); } catch {}
      }
    } catch (e: any) {
      Alert.alert('Error', e.message || 'Could not toggle overlay');
    }
  }, [hasOverlay, overlayActive, strikerMoveable, snapMode, requestOverlay]);

  const toggleAutoDetect = useCallback(async () => {
    if (!overlayActive) {
      Alert.alert('Start Overlay First',
        'Turn on the Aim Overlay before enabling auto-detect.');
      return;
    }
    try {
      if (autoDetect) {
        await OverlayModule.stopScreenCapture();
        setAutoDetect(false);
      } else {
        OverlayModule.setDetectionThreshold(detectThreshold);
        await OverlayModule.requestScreenCapture();
        setTimeout(refreshStatus, 2500);
      }
    } catch (e: any) {
      Alert.alert('Error', e.message || 'Could not toggle screen capture');
    }
  }, [overlayActive, autoDetect, detectThreshold, refreshStatus]);

  const toggleStrikerMoveable = useCallback((val: boolean) => {
    setStrikerMoveable(val);
    try { OverlayModule.setStrikerMoveable(val); } catch {}
  }, []);

  const toggleSnapMode = useCallback((val: boolean) => {
    setSnapMode(val);
    try { OverlayModule.setSnapMode(val); } catch {}
  }, []);

  const handleModeSelect = useCallback((mode: ShotMode) => {
    setSelectedMode(mode);
    try { OverlayModule.setShotMode(mode); } catch {}
  }, []);

  const handleSensitivityChange = useCallback((val: number) => {
    setSensitivity(val);
    try { OverlayModule.setSensitivity(val); } catch {}
  }, []);

  const handleThresholdChange = useCallback((val: number) => {
    setDetectThreshold(val);
    try { OverlayModule.setDetectionThreshold(val); } catch {}
  }, []);

  const handleMarginChange = useCallback(
    (axis: 'X' | 'Y', value: number) => {
      const key = `${activeMarginTab.toLowerCase()}${axis}` as keyof MarginSettings;
      const updated = {...margin, [key]: value};
      setMargin(updated);
      try { OverlayModule.setMarginOffset(updated.d2X, updated.d2Y); } catch {}
    },
    [activeMarginTab, margin],
  );

  const getActiveMargin = () => {
    switch (activeMarginTab) {
      case 'D2':     return {x: margin.d2X, y: margin.d2Y};
      case 'E2':     return {x: margin.e2X, y: margin.e2Y};
      case 'INSIDE': return {x: margin.insideX, y: margin.insideY};
    }
  };

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#0D0D1A" />

      <View style={styles.header}>
        <Text style={styles.logo}>Bit-Aim</Text>
        <Text style={styles.subtitle}>Carrom Aim Assist • v4.14.3 • power meter, auto play, PC brain (optional)</Text>
      </View>

      <ScrollView style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}>

        {/* Permission banner */}
        {!hasOverlay && (
          <TouchableOpacity style={styles.permBanner} onPress={requestOverlay}>
            <Text style={styles.permBannerText}>
              Grant "Display over other apps" to use the overlay
            </Text>
            <Text style={styles.permBannerCta}>Tap to grant →</Text>
          </TouchableOpacity>
        )}

        {/* Main toggles */}
        <View style={styles.card}>
          <ToggleRow
            title="Aim Overlay"
            sub={overlayActive
              ? 'Running in AUTO — tap floating icon to pick a coin'
              : 'Start to draw aim lines on top of Carrom Pool'}
            value={overlayActive}
            onToggle={toggleOverlay}
            color="#FFD700"
          />
          <Divider />
          <ToggleRow
            title="Auto-Detect Board + Pieces"
            sub={autoDetect
              ? 'Reading screen — board, striker and coins detected automatically'
              : 'Use CV to auto-detect board size and pieces (per-session permission)'}
            value={autoDetect}
            onToggle={toggleAutoDetect}
            color="#00E5FF"
          />
        </View>

        {/* Detection sensitivity */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Freeze my colour</Text>
          <Text style={styles.cardSub}>Hold the floating star, then tap White, Black, or another detected colour. The name stays under the star. AUTO can choose any coin in that colour; it will not switch to another colour.</Text>
          <Text style={styles.cardSub}>{targetColourKey===0?'Current selection: Auto / game mode':targetColourKey===1?'Current selection: WHITE LOCK':targetColourKey===2?'Current selection: BLACK LOCK':'Current selection: manual colour lock'}</Text>
          <TouchableOpacity onPress={() => {OverlayModule.setTargetColourKey(0);setTargetColourKey(0);setAllColours(false);}}>
            <Text style={styles.permBannerCta}>Return to Auto team →</Text>
          </TouchableOpacity>
        </View>
        <View style={styles.card}>
          <ToggleRow title="All colours • Freestyle"
            sub={allColours
              ? 'All colours when no team assignment is visible. A detected team badge takes priority and restores automatic mode.'
              : 'Automatic game recognition. Turn ON for all-colour games if the legend is hidden.'}
            value={allColours} color="#22FF6E" onToggle={(enabled: boolean) => {
              setAllColours(enabled);OverlayModule.setAllColours(enabled);
            }} />
        </View>
        <View style={styles.card}>
          <ToggleRow title="Debug mode"
            sub={debugMode
              ? 'Recording while a Carrom board is on screen (● REC under the star): your shots (auto or manual), shots by other players, pictures and problems. Saved only on this phone.'
              : 'Off. Turn on to record shots, pictures and problems for tuning. Also in the star menu.'}
            value={debugMode} color="#FF5252" onToggle={(enabled: boolean) => {
              setDebugMode(enabled);
              try {OverlayModule.setDebugMode(enabled);} catch {}
            }} />
          <Text style={styles.cardSub}>Recordings: {recStats || '—'}</Text>
          <View style={styles.rowSpread}>
            <TouchableOpacity style={[styles.recButton, {borderColor: '#22FF6E'}]} onPress={async () => {
              try {await OverlayModule.shareRecordings();} catch (e: any) {Alert.alert('Share recordings', e.message);}
              refreshRecStats();
            }}>
              <Text style={[styles.recButtonText, {color: '#22FF6E'}]}>Share recordings (zip)</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.recButton, {borderColor: '#FF5252'}]} onPress={() => {
              Alert.alert('Clear recordings', 'Delete all recorded shots and pictures from this phone? Share them first if you want to keep them.', [
                {text: 'Cancel', style: 'cancel'},
                {text: 'Delete', style: 'destructive', onPress: async () => {try {await OverlayModule.clearRecordings();} catch {} refreshRecStats();}},
              ]);
            }}>
              <Text style={[styles.recButtonText, {color: '#FF5252'}]}>Clear recordings</Text>
            </TouchableOpacity>
          </View>
          <Divider />
          <ToggleRow title="Experimental automatic shots"
            sub="Moves the striker and releases shots in Carrom Pool, including online. OFF by default. Tap the floating icon to stop."
            value={autoPlay} color="#FFD700" onToggle={async (enabled: boolean) => {
              try {OverlayModule.setAutoPull(autoPull/100);await OverlayModule.setAutoPlay(enabled);setAutoPlay(enabled);} catch(e: any) {Alert.alert('Gesture control',e.message);}
            }} />
          <Divider />
          <ToggleRow title="PC brain (USB)"
            sub={pcLink
              ? (pcState === 'connected' ? 'Connected: the PC plans every shot and records every frame. Turn off any time, also mid-game (star menu).' : 'On, waiting for the PC program (USB debugging + pc-brain). The phone plans until it connects.')
              : 'Off: the phone plans by itself. Turn on to let a connected PC do detection and planning (faster, smarter). Also in the star menu.'}
            value={pcLink} color="#40C4FF" onToggle={(enabled: boolean) => {
              setPcLink(enabled);
              try {OverlayModule.setPcLink(enabled);} catch {}
              setTimeout(refreshPc, 1500);
            }} />
          <TouchableOpacity onPress={() => OverlayModule.openGestureSettings()}>
            <Text style={styles.permBannerCta}>Enable BitAim gesture control in Android Settings →</Text>
          </TouchableOpacity>
          <Text style={styles.cardSub}>Requires a stable route passing 8/9 model checks. Shot power and slider position are experimental and need calibration. No guaranteed pots.</Text>
          <Text style={styles.cardSub}>Pull distance: {autoPull}% of board width</Text>
          <Slider minimumValue={6} maximumValue={25} step={1} value={autoPull}
            onValueChange={(v: number) => {setAutoPull(v);OverlayModule.setAutoPull(v/100);}}
            minimumTrackTintColor="#FFD700" maximumTrackTintColor="#333" thumbTintColor="#FFD700" />
        </View>
        <View style={styles.card}>
          <View style={styles.rowSpread}>
            <Text style={styles.cardTitle}>Detection Sensitivity</Text>
            <Text style={[styles.valueLabel, {color: '#00E5FF'}]}>{detectThreshold}</Text>
          </View>
          <Text style={styles.cardSub}>
            Lower = more circles detected. Higher = only the clearest ones.
          </Text>
          <Slider style={styles.slider}
            minimumValue={12} maximumValue={40} step={1}
            value={detectThreshold} onValueChange={handleThresholdChange}
            minimumTrackTintColor="#00E5FF" maximumTrackTintColor="#333"
            thumbTintColor="#00E5FF" />
          <View style={styles.rowSpread}>
            <Text style={styles.sliderEndLabel}>Sensitive</Text>
            <Text style={styles.sliderEndLabel}>Strict</Text>
          </View>
        </View>

        {/* How to use */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>How to Use</Text>
          <HowStep n="1" text='Grant "Display over other apps"' />
          <HowStep n="2" text="Turn on Aim Overlay, then Auto-Detect (choose Entire screen)" />
          <HowStep n="3" text="Open Carrom Pool" />
          <HowStep n="4" text="AUTO starts immediately. Team badges identify your colour in singles/doubles; freestyle allows all coin colours." highlight />
          <HowStep n="5" text="Tap the floating icon → PICK. Tap your coin once, lift your finger, then drag and shoot normally." highlight />
          <HowStep n="6" text="Hold the floating star → choose your colour once to freeze it. Hold again → Next route for another numbered option." highlight />
          <HowStep n="7" text="Drag your aim: the suggested striker line turns green after the game's white dots align in three separate captures. Orange marks transfers or cluster contact. ★ suggests repositioning." />
          <HowStep n="8" text="To hide lines: tap the icon into PICK, then tap the icon again." />
          <HowStep n="9" text="Hold the star → Pro style: ranks pot + best position for the next shot (rail pots, cluster breaks) instead of easiest aim. The star caption shows • Pro." highlight />
          <HowStep n="10" text="EASY / OK / TIGHT / RISKY next to the aim label is how many degrees of aim error the model still pots: EASY is 2° or more, TIGHT means half a degree. Prefer ★ when it offers an easier spot." highlight />
          <View style={styles.tipBox}>
            <Text style={styles.tipText}>
              A valid target stays selected while you aim. Picks release touches
              when you lift your finger. Cushion and combination routes are
              estimates; game power and spin are not calibrated. OPEN CLUSTER
              shows first contact and simulated spread, not a promised pot.
              Cluster-opening shots remain manual. AIM ALIGNED checks direction,
              not power or a guaranteed result. Pot + open cluster identifies
              a simulated pot that also moves other coins and opens a close pair.
              QUEEN FIRST follows the last-own-coin priority; queen cover remains manual.
            </Text>
          </View>
        </View>

        <View style={styles.footer}>
          <Text style={styles.footerText}>
            Bit-Aim v4.14.3 • Chance ~% is a simulated estimate with hand aim error, power and pocket variation, not measured game odds. Power and spin are not calibrated.
          </Text>
        </View>
      </ScrollView>
    </View>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

function ToggleRow({title, sub, value, onToggle, color}: {
  title: string; sub: string; value: boolean;
  onToggle: (v: boolean) => void; color: string;
}) {
  return (
    <View style={styles.row}>
      <View style={{flex: 1, paddingRight: 10}}>
        <Text style={styles.cardTitle}>{title}</Text>
        <Text style={styles.cardSub}>{sub}</Text>
      </View>
      <Switch value={value} onValueChange={onToggle}
        trackColor={{false: '#333', true: color}}
        thumbColor={value ? '#FFF' : '#888'} />
    </View>
  );
}

function Divider() {
  return <View style={{height: 1, backgroundColor: '#222244', marginVertical: 12}} />;
}

function LegendDot({color, label}: {color: string; label: string}) {
  return (
    <View style={styles.legendItem}>
      <View style={[styles.legendSwatch, {backgroundColor: color}]} />
      <Text style={styles.legendLabel}>{label}</Text>
    </View>
  );
}

function HowStep({n, text, highlight}: {n: string; text: string; highlight?: boolean}) {
  return (
    <View style={styles.stepRow}>
      <View style={[styles.stepBadge, highlight && styles.stepBadgeHL]}>
        <Text style={[styles.stepNum, highlight && {color: '#22FF6E'}]}>{n}</Text>
      </View>
      <Text style={[styles.howToStep, highlight && {color: '#22FF6E', fontWeight: '700'}]}>
        {text}
      </Text>
    </View>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  recButton: {flex: 1, marginTop: 8, marginHorizontal: 4, paddingVertical: 10, borderWidth: 1, borderRadius: 10, alignItems: 'center'},
  recButtonText: {fontSize: 13, fontWeight: '700'},
  root: {flex: 1, backgroundColor: '#0D0D1A'},
  header: {
    paddingTop: Platform.OS === 'android' ? StatusBar.currentHeight ?? 24 : 44,
    paddingBottom: 16, paddingHorizontal: 20,
    backgroundColor: '#13132A', borderBottomWidth: 1, borderBottomColor: '#222244',
  },
  logo:     {color: '#FFD700', fontSize: 26, fontWeight: '900', letterSpacing: 1},
  subtitle: {color: '#8888BB', fontSize: 12, marginTop: 2},
  scroll:        {flex: 1},
  scrollContent: {padding: 16, paddingBottom: 40},
  permBanner: {
    backgroundColor: '#2A1A00', borderWidth: 1, borderColor: '#FFD700',
    borderRadius: 10, padding: 14, marginBottom: 12,
  },
  permBannerText: {color: '#FFC', fontSize: 13},
  permBannerCta:  {color: '#FFD700', fontSize: 13, fontWeight: '700', marginTop: 4},
  card: {
    backgroundColor: '#16162E', borderRadius: 14, padding: 16,
    marginBottom: 14, borderWidth: 1, borderColor: '#222244',
  },
  snapCard:        {borderColor: '#22FF6E44', backgroundColor: '#071A0E'},
  snapCardTitle:   {color: '#22FF6E', fontSize: 15, fontWeight: '700', marginBottom: 8},
  snapCardBody:    {color: '#A7F3D0', fontSize: 12, lineHeight: 18},
  snapLegend:      {flexDirection: 'row', alignItems: 'center', marginTop: 12},
  snapSwatch:      {width: 14, height: 4, borderRadius: 2, marginRight: 6},
  snapLegendText:  {color: '#A7F3D0', fontSize: 11},
  cardTitle: {color: '#FFFFFF', fontSize: 15, fontWeight: '700', marginBottom: 3},
  cardSub:   {color: '#8888BB', fontSize: 12, marginBottom: 4},
  row:       {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'},
  rowSpread: {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center'},
  shotGrid:  {flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8},
  shotBtn: {
    width: '47%', backgroundColor: '#1E1E3A', borderRadius: 10, padding: 12,
    borderWidth: 1.5, borderColor: '#333355', alignItems: 'flex-start',
  },
  shotBtnActive:   {borderColor: '#00E5FF', backgroundColor: '#00151F'},
  shotLabel:       {color: '#AAA', fontSize: 14, fontWeight: '700'},
  shotLabelActive: {color: '#00E5FF'},
  shotDesc:        {color: '#666688', fontSize: 10, marginTop: 3},
  legend:          {flexDirection: 'row', flexWrap: 'wrap', gap: 10, marginTop: 12},
  legendItem:      {flexDirection: 'row', alignItems: 'center'},
  legendSwatch:    {width: 14, height: 4, borderRadius: 2, marginRight: 6},
  legendLabel:     {color: '#AAA', fontSize: 11},
  slider:          {width: '100%', height: 36},
  sliderEndLabel:  {color: '#666688', fontSize: 11},
  valueLabel:      {color: '#FFD700', fontSize: 16, fontWeight: '700'},
  tabRow:  {flexDirection: 'row', gap: 8, marginVertical: 10},
  tab: {
    flex: 1, paddingVertical: 8, borderRadius: 8,
    backgroundColor: '#1E1E3A', alignItems: 'center',
    borderWidth: 1, borderColor: '#333355',
  },
  tabActive:     {backgroundColor: '#00293A', borderColor: '#00E5FF'},
  tabText:       {color: '#8888BB', fontSize: 13, fontWeight: '600'},
  tabTextActive: {color: '#00E5FF'},
  marginRow:   {marginBottom: 8},
  marginLabel: {color: '#AAA', fontSize: 13, marginBottom: 2},
  marginValue: {color: '#00E5FF', fontWeight: '700'},
  resetBtn: {
    marginTop: 6, paddingVertical: 8, borderRadius: 8,
    backgroundColor: '#1E1E3A', alignItems: 'center',
    borderWidth: 1, borderColor: '#444466',
  },
  resetBtnText: {color: '#FF7777', fontSize: 13, fontWeight: '600'},
  stepRow:     {flexDirection: 'row', alignItems: 'flex-start', marginBottom: 8},
  stepBadge: {
    width: 22, height: 22, borderRadius: 11, backgroundColor: '#1E1E3A',
    alignItems: 'center', justifyContent: 'center', marginRight: 10, marginTop: 1,
    borderWidth: 1, borderColor: '#333355',
  },
  stepBadgeHL: {borderColor: '#22FF6E'},
  stepNum:     {color: '#8888BB', fontSize: 11, fontWeight: '700'},
  howToStep:   {color: '#CCCCEE', fontSize: 13, flex: 1},
  tipBox:      {
    backgroundColor: '#071A0E', padding: 10, borderRadius: 8, marginTop: 8,
    borderWidth: 1, borderColor: '#22FF6E44',
  },
  tipText:  {color: '#A7F3D0', fontSize: 12},
  footer:   {alignItems: 'center', marginTop: 10},
  footerText: {color: '#444466', fontSize: 11},
});
