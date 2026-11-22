import {NativeModules} from 'react-native';

export const settings = [
  {key: 'blockReelsButton', group: 'Instagram', label: 'Block Reels button', hint: 'Prevents tapping the Reels tab.'},
  {key: 'blockReelsScroll', group: 'Instagram', label: 'Block Reels scrolling', hint: 'Blocks video swipes while allowing taps on controls.'},
  {key: 'allowDmReels', group: 'Instagram', label: 'Allow Reels from DMs', hint: 'Allows detected Reel openings from Instagram messages.'},
  {key: 'blockShortsButton', group: 'YouTube', label: 'Block Shorts button', hint: 'Prevents tapping the Shorts tab.'},
  {key: 'blockShortsScroll', group: 'YouTube', label: 'Block Shorts scrolling', hint: 'Blocks video swipes while allowing taps on controls.'},
] as const;

export type SettingKey = typeof settings[number]['key'];
export type BlockerState = Record<SettingKey | 'enabled', boolean> & {
  serviceEnabled: boolean;
  serviceRunning: boolean;
};

interface BlockerModule {
  getState(): Promise<BlockerState>;
  setSetting(key: SettingKey | 'enabled', value: boolean): Promise<BlockerState>;
  openAccessibilitySettings(): Promise<void>;
}

export const blocker: BlockerModule | undefined = NativeModules.Blocker;