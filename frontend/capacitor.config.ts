import type {CapacitorConfig} from '@capacitor/cli';

const config: CapacitorConfig = {
    appId: 'com.junglebell.mobile',
    appName: 'Jungle Bell',
    webDir: 'dist/web',
    // The web build assumes a same-origin API (relative /api calls). Point the
    // WebView at the production origin so the app behaves exactly like the PWA.
    server: {
        url: 'https://jungle-bell.sijun-yang.com',
        cleartext: false,
    },
};

export default config;
