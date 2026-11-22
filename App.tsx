import React, {useEffect, useState} from 'react';
import {AppState, Modal, Platform, Pressable, ScrollView, StatusBar, StyleSheet, Switch, Text, View} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {blocker, BlockerState, SettingKey, settings} from './src/blocker';

function SettingsScreen() {
  const [state, setState] = useState<BlockerState>();
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [consentVisible, setConsentVisible] = useState(false);
  const supported = Platform.OS === 'android' && !!blocker;

  useEffect(() => {
    const nativeBlocker = blocker;
    if (!nativeBlocker) { return; }
    let mounted = true;
    const refresh = async () => {
      try {
        const nextState = await nativeBlocker.getState();
        if (mounted) { setState(nextState); setError(''); }
      } catch {
        if (mounted) { setError('Unable to read protection settings.'); }
      }
    };
    void refresh();
    const listener = AppState.addEventListener('change', status => {
      if (status === 'active') { void refresh(); }
    });
    return () => { mounted = false; listener.remove(); };
  }, []);

  async function update(key: SettingKey | 'enabled', value: boolean) {
    if (!blocker) { return; }
    setSaving(true);
    try {
      setState(await blocker.setSetting(key, value));
      setError('');
    } catch { setError('Setting was not saved. Try again.'); }
    finally { setSaving(false); }
  }

  async function enable() {
    if (!blocker) { return; }
    try {
      await blocker.openAccessibilitySettings();
      setConsentVisible(false);
      setError('');
    } catch { setError('Unable to open Android accessibility settings.'); }
  }

  const status = !supported ? 'Android blocker unavailable' : !state ? 'Loading settings'
    : !state.serviceEnabled ? 'Accessibility permission required'
    : !state.enabled ? 'Protection paused'
    : state.serviceRunning ? 'Protection active' : 'Service is starting';

  return (
    <SafeAreaView style={styles.screen}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text accessibilityRole="header" style={styles.title}>Block Reels</Text>
        <Text accessibilityLiveRegion="polite" style={styles.status}>{status}</Text>
        {!supported && <Text style={styles.note}>Install the Android build to enable protection.</Text>}
        {(['Instagram', 'YouTube'] as const).map(group => (
          <View key={group} style={styles.section}>
            <Text accessibilityRole="header" style={styles.heading}>{group}</Text>
            {settings.filter(setting => setting.group === group).map(setting => (
              <View key={setting.key} style={styles.row}>
                <Text style={styles.label}>{setting.label}</Text>
                <Switch testID={setting.key} accessibilityLabel={setting.label}
                  accessibilityHint={setting.hint} disabled={!supported || !state || saving}
                  value={state?.[setting.key] ?? false}
                  onValueChange={value => void update(setting.key, value)}
                  trackColor={{false: '#333333', true: '#aaaaaa'}} thumbColor="#ffffff"
                  ios_backgroundColor="#333333" />
              </View>
            ))}
          </View>
        ))}
        {supported && state && (
          <Pressable testID="protectionAction" accessibilityRole="button" disabled={saving} style={styles.action}
            onPress={() => state.serviceEnabled ? void update('enabled', !state.enabled) : setConsentVisible(true)}>
            <Text style={styles.actionText}>{!state.serviceEnabled ? 'Enable protection'
              : state.enabled ? 'Pause protection' : 'Resume protection'}</Text>
          </Pressable>
        )}
        {!!error && <Text accessibilityRole="alert" style={styles.note}>{error}</Text>}
      </ScrollView>
      <Modal visible={consentVisible} transparent animationType="fade" onRequestClose={() => setConsentVisible(false)}>
        <View style={styles.modalBackdrop}>
          <ScrollView accessibilityViewIsModal style={styles.dialog} contentContainerStyle={styles.dialogContent}>
            <Text accessibilityRole="header" style={styles.heading}>Accessibility access</Text>
            <Text style={styles.disclosure}>
              Block Reels reads visible Instagram and YouTube interface labels and layout to detect
              video screens and place touch-blocking overlays. This can expose visible messages to
              the service. Nothing is recorded, stored, or sent off your device. No screenshots
              are taken. You can disable access in Android Settings at any time.
            </Text>
            <Text style={styles.disclosure}>
              Scroll blocking filters swipes and forwards taps to the app, including likes,
              comments, sharing, and saving. Controls and panel detection depend on the app
              version. DM detection may not recognize every shared Reel.
            </Text>
            <Pressable testID="consentAction" accessibilityRole="button" style={styles.action} onPress={() => void enable()}>
              <Text style={styles.actionText}>Agree and open Settings</Text>
            </Pressable>
            <Pressable accessibilityRole="button" style={styles.action} onPress={() => setConsentVisible(false)}>
              <Text style={styles.actionText}>Cancel</Text>
            </Pressable>
          </ScrollView>
        </View>
      </Modal>
    </SafeAreaView>
  );
}

export default function App() {
  return <SafeAreaProvider><StatusBar barStyle="light-content" /><SettingsScreen /></SafeAreaProvider>;
}

const styles = StyleSheet.create({
  screen: {flex: 1, backgroundColor: '#000000'},
  content: {padding: 24, paddingBottom: 40, width: '100%', maxWidth: 560, alignSelf: 'center'},
  title: {color: '#ffffff', fontSize: 28, fontWeight: '600', marginBottom: 12},
  status: {color: '#bbbbbb', fontSize: 14, marginBottom: 32},
  section: {marginBottom: 28},
  heading: {color: '#ffffff', fontSize: 18, fontWeight: '600', marginBottom: 12},
  row: {minHeight: 64, flexDirection: 'row', alignItems: 'center', borderBottomWidth: 1, borderBottomColor: '#222222', gap: 16},
  label: {color: '#ffffff', fontSize: 16, flex: 1, flexShrink: 1},
  action: {minHeight: 48, justifyContent: 'center', paddingVertical: 12},
  actionText: {color: '#ffffff', fontSize: 16, textDecorationLine: 'underline'},
  note: {color: '#bbbbbb', fontSize: 14, lineHeight: 22, marginTop: 12},
  modalBackdrop: {flex: 1, backgroundColor: '#000000cc', justifyContent: 'center', padding: 24},
  dialog: {backgroundColor: '#111111', borderRadius: 8, width: '100%', maxWidth: 560, maxHeight: '90%', alignSelf: 'center', flexGrow: 0},
  dialogContent: {padding: 24},
  disclosure: {color: '#ffffff', fontSize: 14, lineHeight: 22, marginBottom: 16},
});
