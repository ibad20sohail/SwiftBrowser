/*******************************************************************************

    Swift browser bridge for uBlock Origin.

    The Swift app bundles uBlock Origin as a built-in GeckoView extension and
    uses this module to apply its "Off / Balanced / High / Extreme" levels:
    which filter lists are enabled, uBO's dynamic rules (Extreme blocks
    third-party scripts and frames) and the trusted-site list (blocking off).

    The app always sends the full desired state; only differences are applied,
    so filter lists are recompiled only when the selection actually changes.

*******************************************************************************/

import { permanentFirewall, sessionFirewall } from './filtering-engines.js';
import cosmeticFilteringEngine from './cosmetic-filtering.js';
import { filteringBehaviorChanged } from './broadcast.js';
import µb from './background.js';

const NATIVE_APP = 'swift';

let port = null;
let queue = Promise.resolve();

const post = msg => {
    try {
        if ( port !== null ) { port.postMessage(msg); }
    } catch {
    }
};

const normalize = text => text
    .split(/\s*[\n\r]+\s*/)
    .map(s => s.trim())
    .filter(s => s !== '' && s.startsWith('#') === false)
    .sort()
    .join('\n');

const sameSet = (a, b) => {
    if ( a.size !== b.size ) { return false; }
    for ( const k of a ) {
        if ( b.has(k) === false ) { return false; }
    }
    return true;
};

/*
 * Settings tuned for a slow phone. Applied on every connect; only changed
 * values are written.
 *  - Never pause the whole network while filter lists load: on a slow phone a
 *    list reload could leave pages and searches hanging for a long time. The
 *    app waits briefly for uBO at startup instead.
 *  - Ignore generic cosmetic filters: skips scanning every DOM change on busy
 *    pages (YouTube etc.). Network blocking and site-specific hiding still apply.
 *  - Save the fast-start "selfie" 5 s after filters load (default 53 s), so a
 *    quickly closed app doesn't recompile every list on the next launch.
 *  - No list updates on uBO's own timer: rebuilding the filters after an update
 *    keeps uBO busy for a long time on a slow phone, and pages wait for it
 *    meanwhile. The app asks for updates ('update') while it is in the
 *    background instead.
 */
const tuneForPhone = ( ) => {
    const us = µb.userSettings;
    if ( us.suspendUntilListsAreLoaded !== false ) {
        µb.changeUserSettings('suspendUntilListsAreLoaded', false);
    }
    if ( us.ignoreGenericCosmeticFilters !== true ) {
        µb.changeUserSettings('ignoreGenericCosmeticFilters', true);
    }
    if ( us.autoUpdate !== false ) {
        µb.changeUserSettings('autoUpdate', false);
    }
    const hs = µb.hiddenSettings;
    if ( hs.selfieDelayInSeconds !== 5 ) {
        µb.changeHiddenSettings(Object.assign({}, hs, {
            selfieDelayInSeconds: 5,
        }));
    }
};

/*
 * uBO holds every network request while it (re)loads filter lists: at launch,
 * when the list selection changes and after its automatic list updates. On a
 * slow phone that can take tens of seconds, so pages and searches appear to
 * hang, and if loading never finishes nothing loads until the app's data is
 * cleared. Release held requests after a short moment instead; they are then
 * filtered with whatever is already loaded.
 */
const MAX_SUSPEND_MS = 1500;

const limitNetworkSuspension = ( ) => {
    const net = vAPI.net;
    if ( net instanceof Object === false || net.swiftLimited === true ) { return; }
    net.swiftLimited = true;
    const suspend = net.suspend.bind(net);
    let timer;
    net.suspend = function() {
        suspend();
        clearTimeout(timer);
        timer = setTimeout(( ) => {
            if ( net.suspendDepth !== 0 ) {
                net.unsuspend({ all: true });
            }
        }, MAX_SUSPEND_MS);
    };
    // A suspension that started before this module loaded.
    if ( net.suspendDepth !== 0 ) {
        timer = setTimeout(( ) => {
            if ( net.suspendDepth !== 0 ) {
                net.unsuspend({ all: true });
            }
        }, MAX_SUSPEND_MS);
    }
};

limitNetworkSuspension();

const apply = async msg => {
    await µb.isReadyPromise;
    tuneForPhone();
    let reloaded = false;

    if ( Array.isArray(msg.lists) && msg.lists.length !== 0 ) {
        const want = new Set(msg.lists);
        const have = new Set(µb.selectedFilterLists);
        if ( sameSet(want, have) === false ) {
            µb.applyFilterListSelection({ toSelect: Array.from(want) });
            await µb.loadFilterLists();
            reloaded = true;
        }
    }

    if ( typeof msg.rules === 'string' ) {
        // uBO only evaluates dynamic hostname rules in "advanced user" mode.
        if ( /\sblock\s*$/m.test(msg.rules) && µb.userSettings.advancedUserEnabled !== true ) {
            µb.changeUserSettings('advancedUserEnabled', true);
        }
        if ( normalize(permanentFirewall.toString()) !== normalize(msg.rules) ) {
            permanentFirewall.fromString(msg.rules);
            sessionFirewall.assign(permanentFirewall);
            µb.savePermanentFirewallRules();
            cosmeticFilteringEngine.removeFromSelectorCache('*');
            filteringBehaviorChanged();
        }
    }

    if ( Array.isArray(msg.trusted) ) {
        const directives = (µb.netWhitelistDefault || []).concat(msg.trusted);
        const want = normalize(directives.join('\n'));
        const have = normalize(µb.stringFromWhitelist(µb.netWhitelist));
        if ( want !== have ) {
            µb.netWhitelist = µb.whitelistFromArray(directives);
            µb.saveWhitelist();
            filteringBehaviorChanged();
        }
    }

    post({ type: 'applied', reloaded });
};

const onMessage = msg => {
    if ( msg instanceof Object === false ) { return; }
    switch ( msg.type ) {
    case 'apply':
        queue = queue.then(( ) => apply(msg)).catch(reason => {
            post({ type: 'log', text: `apply failed: ${reason}` });
        });
        break;
    case 'update':
        µb.isReadyPromise.then(( ) => {
            µb.scheduleAssetUpdater({ now: true, fetchDelay: 100, auto: true });
        });
        break;
    default:
        break;
    }
};

const connect = ( ) => {
    try {
        port = browser.runtime.connectNative(NATIVE_APP);
    } catch (reason) {
        console.error('Swift bridge: connectNative failed', reason);
        port = null;
        return;
    }
    port.onMessage.addListener(onMessage);
    port.onDisconnect.addListener(( ) => {
        port = null;
    });
    µb.isReadyPromise.then(( ) => {
        post({ type: 'ready', lists: µb.selectedFilterLists });
    });
};

connect();
