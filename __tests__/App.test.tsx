import React from 'react';
import {NativeModules, Platform, Switch} from 'react-native';
import {act, create, ReactTestRenderer} from 'react-test-renderer';

jest.setTimeout(30000);
jest.mock('react-native-safe-area-context', () => require('react-native-safe-area-context/jest/mock').default);
Platform.OS = 'android';

const initialState = {
  enabled: true, blockReelsButton: true, blockReelsScroll: true, allowDmReels: true,
  blockShortsButton: true, blockShortsScroll: true, serviceEnabled: false, serviceRunning: false,
};
NativeModules.Blocker = {
  getState: jest.fn().mockResolvedValue(initialState),
  setSetting: jest.fn(),
  openAccessibilitySettings: jest.fn().mockResolvedValue(undefined),
};
const App = require('../App').default;
let renderer: ReactTestRenderer;
beforeEach(async () => {
  jest.clearAllMocks();
  NativeModules.Blocker.getState.mockResolvedValue(initialState);
  await act(async () => { renderer = create(<App />); });
});
afterEach(async () => { await act(async () => renderer.unmount()); });

test('renders five independent settings and saves through native storage', async () => {
  expect(renderer.root.findAllByType(Switch)).toHaveLength(5);
  NativeModules.Blocker.setSetting.mockResolvedValue({...initialState, blockReelsScroll: false});
  await act(async () => {
    renderer.root.findByProps({testID: 'blockReelsScroll'}).props.onValueChange(false);
  });
  expect(NativeModules.Blocker.setSetting).toHaveBeenCalledWith('blockReelsScroll', false);
  expect(renderer.root.findByProps({testID: 'blockReelsScroll'}).props.value).toBe(false);
  expect(renderer.root.findByProps({testID: 'blockShortsScroll'}).props.value).toBe(true);
});

test('requires affirmative consent before opening accessibility settings', async () => {
  await act(async () => renderer.root.findByProps({testID: 'protectionAction'}).props.onPress());
  expect(NativeModules.Blocker.openAccessibilitySettings).not.toHaveBeenCalled();
  await act(async () => renderer.root.findByProps({testID: 'consentAction'}).props.onPress());
  expect(NativeModules.Blocker.openAccessibilitySettings).toHaveBeenCalledTimes(1);
});

test('does not show an unsaved toggle after native storage rejects a change', async () => {
  NativeModules.Blocker.setSetting.mockRejectedValue(new Error('Storage failed'));
  await act(async () => {
    renderer.root.findByProps({testID: 'blockReelsButton'}).props.onValueChange(false);
  });
  expect(renderer.root.findByProps({testID: 'blockReelsButton'}).props.value).toBe(true);
  expect(renderer.root.findByProps({accessibilityRole: 'alert'}).props.children).toContain('not saved');
});
