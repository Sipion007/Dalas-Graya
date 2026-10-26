// Service worker Dallas Bouff' : notifications push (restaurateurs, livreurs, clients, administrateur)
const SB_URL = 'https://gdkcrazrejzpwzxbhpxp.supabase.co';
const SB_KEY = 'sb_publishable_1cijZWLbC-Em1Q9o3UwpvA_VLmNtmwi';

self.addEventListener('install', () => { self.skipWaiting(); });
self.addEventListener('activate', (event) => { event.waitUntil(self.clients.claim()); });

// Vérifie qu'une livraison proposée est toujours libre au moment où la notification arrive
async function orderState(orderId) {
  try {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 4000);
    const res = await fetch(SB_URL + '/rest/v1/rpc/order_public_state', {
      method: 'POST', signal: ctrl.signal,
      headers: { apikey: SB_KEY, 'Content-Type': 'application/json' },
      body: JSON.stringify({ p_order: orderId })
    });
    clearTimeout(timer);
    if (!res.ok) return null;
    return await res.json();
  } catch (e) { return null; }
}

function ago(ms) {
  const m = Math.round(ms / 60000);
  return m < 60 ? m + ' min' : Math.floor(m / 60) + ' h ' + String(m % 60).padStart(2, '0');
}

self.addEventListener('push', (event) => {
  let data = { title: "Dallas Bouff'", body: 'Nouvelle alerte.', url: '/', tag: 'dbf' };
  try { if (event.data) data = Object.assign(data, event.data.json()); } catch (e) {}

  event.waitUntil((async () => {
    let title = data.title, body = data.body, quiet = !!data.silent;
    if (data.check) {
      const st = await orderState(data.check);
      if (st && st !== 'available') {
        title = st === 'cancelled' ? 'Livraison annulée' : 'Course déjà prise';
        body = st === 'cancelled' ? 'Cette commande a été annulée, inutile de vous déplacer.' : 'Un autre livreur a déjà pris cette livraison.';
        quiet = true;
      }
    }
    const late = data.ts ? Date.now() - Number(data.ts) : 0;
    if (!quiet && late > 120000) body = '(envoyée il y a ' + ago(late) + ') ' + body;
    const options = quiet ? {
      body, icon: 'icons/icon-192.png', badge: 'icons/icon-192.png', tag: data.tag,
      renotify: false, silent: true, requireInteraction: false, data: { url: data.url || '/' }
    } : {
      body, icon: 'icons/icon-192.png', badge: 'icons/icon-192.png', tag: data.tag,
      renotify: true, requireInteraction: true,
      vibrate: [500, 200, 500, 200, 500, 200, 500],
      data: { url: data.url || '/' }
    };
    await self.registration.showNotification(title, options);
    const list = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    list.forEach((c) => { try { c.postMessage({ type: 'push' }); } catch (e) {} });
  })());
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || '/';
  const page = url.split('?')[0];
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
      for (const c of list) {
        if (c.url.includes(page) && 'focus' in c) return c.focus();
      }
      if (self.clients.openWindow) return self.clients.openWindow(url);
    })
  );
});
