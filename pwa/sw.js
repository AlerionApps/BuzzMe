// BuzzMe Service Worker (sw.js)
// Zero analytics, zero trackers. Fully compliant with iOS Safari Web Push & APNs wake.

self.addEventListener('install', (event) => {
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(self.clients.claim());
});

self.addEventListener('push', (event) => {
    let title = "⚡ BUZZ!";
    let body = "Household pager alert received!";

    if (event.data) {
        try {
            const data = event.data.json();
            if (data.title) title = data.title;
            if (data.body) body = data.body;
        } catch (e) {
            body = event.data.text();
        }
    }

    const options = {
        body: body,
        icon: 'icon-192.png',
        badge: 'icon-192.png',
        tag: 'buzzme-alert-' + Date.now(),
        renotify: true,
        requireInteraction: true,
        data: {
            url: self.registration.scope
        }
    };

    event.waitUntil(
        self.registration.showNotification(title, options)
    );
});

self.addEventListener('notificationclick', (event) => {
    event.notification.close();
    event.waitUntil(
        clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
            for (const client of clientList) {
                if (client.url === event.notification.data.url && 'focus' in client) {
                    return client.focus();
                }
            }
            if (clients.openWindow) {
                return clients.openWindow(event.notification.data.url || '/');
            }
        })
    );
});
