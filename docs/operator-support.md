# Sessions, consignes et diagnostic opérateur (T06–T09)

Les routes `/handling-orders/operator/support` utilisent le JWT et le magasin de
l'employé actif. Les profils, consignes, destinataires et diagnostics sont conservés
par la migration additive V3. Les contrôles du magasin s'appliquent côté Java.

- `GET/PUT /session` : état AVAILABLE/PAUSED/FINISHED, taille 100/115/130,
  langue fr/en, son et vibration. L'état de service ne clôture aucune mission.
- `GET /events?storeId=&after=&limit=&category=` : curseur croissant, audience
  individuelle, pagination avec `nextCursor` et `hasMore`.
- `POST /events/{id}/received` et `/ack` : reçus idempotents du destinataire.
- `GET/POST /instructions` : manager/admin, magasin et mission contrôlés,
  cible parmi les utilisateurs actifs ayant accès au support opérateur.
- `GET /health` : disponibilité base/événements et état du moteur sémantique.
- `POST /diagnostics` : liste blanche de données techniques, limite de 16 Kio,
  dix diagnostics par heure et utilisateur, clé de relance stable et suivi UUID.
  `GET /diagnostics` est réservé aux responsables du magasin.

Une confirmation métier et son événement sont écrits dans la même transaction.
Le rejeu retrouve le reçu et ne crée pas un second effet métier. Un curseur sert à
relire les événements ; il n'est jamais utilisé pour rejouer une commande.

Le picker garde la session en mémoire. La déconnexion efface jeton, formulaires,
travail courant et boîte de réception locale. Seule l'adresse serveur reste dans
les préférences du terminal. Le lancement suivant demande une connexion. Les
préférences et accusés côté serveur restent disponibles. Les consignes n'ont pas
de suppression automatique ; les diagnostics sont purgés après 14 jours.

Le panneau Consignes/diagnostics de la tuile Événements utilise la même API.
Il affiche la réception et l'acquittement par destinataire et parcourt les pages
pour ne pas masquer les nouvelles consignes après les trente premières.

## Vérifications conservées

`docs/picker-suivi/preuves-t06-t10` contient les résultats des tests backend,
Storepilot et Android, le parcours instrumenté sur émulateur, ses captures,
les états comparés avant/après redémarrage et le contrat OpenAPI (63 opérations,
16 DTO de requête). Les tests instrumentés utilisent les comptes de démonstration
locaux. Ils ne confirment aucune manutention et ne modifient pas les quantités.

Les diagnostics ne comprennent ni jeton, ni mot de passe, ni texte de scan.
Le test de panne coupe réellement la connexion HTTP de l'application vers une
adresse locale indisponible, puis rétablit le transport et renvoie le même paquet.
La conservation durable est vérifiée par un arrêt/redémarrage distinct du backend.
