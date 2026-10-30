import { P } from '../Squaremap.js';

class Player {
    constructor(json) {
        this.name = json.name;
        this.uuid = json.uuid;
        this.world = json.world;
        this.displayName = json.display_name !== undefined ? json.display_name : json.name;
        this.x = json.x;
        this.z = json.z;
        this.armor = json.armor;
        this.health = json.health;
        this.tooltip = L.tooltip({
            permanent: true,
            direction: "right",
            offset: [10, 0],
            pane: "nameplate"
        });
        this.marker = null;
        this.syncMarker(json);
    }
    syncMarker(json) {
        const world = P.worldList.curWorld;
        if (world == null || world.name !== this.world || !world.player_tracker.enabled
            || !Number.isFinite(json.x) || !Number.isFinite(json.z)) {
            this.removeMarker();
            return;
        }
        const latlng = P.toLatLng(json.x, json.z);
        const angle = 180 + (json.yaw ?? 0);
        if (this.marker == null) {
            this.marker = L.marker(latlng, {
                icon: L.icon({
                    iconUrl: 'images/icon/player.png',
                    iconSize: [17, 16],
                    iconAnchor: [8, 9],
                    tooltipAnchor: [0, 0]
                }),
                rotationAngle: angle
            });
            if (world.player_tracker.nameplates.enabled) {
                this.updateNameplate(json);
                this.marker.bindTooltip(this.tooltip);
            }
        } else {
            this.marker.setLatLng(latlng);
            this.marker.setRotationAngle(angle);
        }
        this.addMarker();
    }
    getHeadUrl() {
        const configured = P.worldList.curWorld.player_tracker.nameplates.heads_url ?? '';
        // Older configs defaulted to mc-heads.net, which is often unreachable
        // from mainland China. Prefer the server-side, same-origin cache.
        if (configured === '' || configured.includes('mc-heads.net')) {
            return `avatars/${this.uuid}.png`;
        }
        return configured
            .replace(/{uuid}/g, this.uuid)
            .replace(/{name}/g, this.name);
    }
    configureListHead(image) {
        const url = this.getHeadUrl();
        image.dataset.headUrl = url;
        image.onload = () => {
            if (!image.currentSrc.endsWith('/images/icon/player.png')) {
                image.dataset.headFallback = 'false';
            }
        };
        image.onerror = () => {
            image.dataset.headFallback = 'true';
            image.src = 'images/icon/player.png';
        };
        image.src = url;
    }
    retryListHead(image) {
        if (image.dataset.headFallback !== 'true' || P.tick_count % 30 !== 0) {
            return;
        }
        image.src = `${image.dataset.headUrl}?retry=${Date.now()}`;
    }
    updateNameplate(player) {
        let headImg = "";
        let armorImg = "";
        let healthImg = "";
        if (P.worldList.curWorld.player_tracker.nameplates.show_heads) {
            headImg = `<img src='${this.getHeadUrl()}' class="head" alt="" onerror="this.onerror=null;this.src='images/icon/player.png'" />`;
        }
        if (P.worldList.curWorld.player_tracker.nameplates.show_armor && player.armor != null) {
            armorImg = `<img src="images/armor/${Math.min(Math.max(player.armor, 0), 20)}.png" class="armor" />`;
        }
        if (P.worldList.curWorld.player_tracker.nameplates.show_health && player.health != null) {
            healthImg = `<img src="images/health/${Math.min(Math.max(player.health, 0), 20)}.png" class="health" />`;
        }
        this.tooltip.setContent(`<ul><li>${headImg}</li><li>${this.displayName}${healthImg}${armorImg}</li>`);
    }
    update(player) {
        this.x = player.x;
        this.z = player.z;
        this.world = player.world;
        this.armor = player.armor;
        this.health = player.health;
        this.displayName = player.display_name !== undefined ? player.display_name : player.name;
        const link = document.getElementById(player.uuid);
        if (link != null) {
            const img = link.getElementsByTagName("img")[0];
            const span = link.getElementsByTagName("span")[0];
            this.retryListHead(img);
            const otherWorld = P.worldList.curWorld.name !== player.world;
            img.classList.toggle("other-world", otherWorld);
            span.classList.toggle("other-world", otherWorld);
        }
        this.syncMarker(player);
        this.updateNameplate(player);
    }
    removeMarker() {
        P.playerList.markers.delete(this.uuid);
        if (this.marker == null) {
            return;
        }
        this.marker.remove();
        P.map.removeLayer(this.marker);
        P.layerControl.playersLayer?.removeLayer(this.marker);
        this.marker = null;
    }
    addMarker() {
        if (this.marker != null && !P.playerList.markers.has(this.uuid)) {
            this.marker.addTo(P.layerControl.playersLayer);
            P.playerList.markers.set(this.uuid, this.marker);
        }
    }
}

export { Player };
