# Contexte et commandes opérateur (T03–T05)

## Contexte public

`GET /handling-orders/operator/context` retourne l'identité publique, les magasins
accessibles, les capacités, les versions de contrat et l'heure serveur avec fuseau.
`?storeId=` restreint la réponse à un magasin autorisé. Aucun compte JPA, hash de mot
de passe, adresse email ou jeton de réinitialisation n'est sérialisé.

L'identité JWT et le compte courant doivent être actifs et leurs rôles compatibles.
Un ancien JWT ne réactive pas un compte désactivé. Cette règle vaut pour toutes les routes
(`AccountStateAugmentor`) : à chaque requête, le compte du jeton est relu ; un compte
supprimé ou désactivé est refusé (401), un jeton émis avant le dernier changement de mot de
passe (`credentials_changed_at`) est refusé, et les rôles appliqués sont ceux du compte, pas
ceux écrits dans le jeton. Le picker reste limité à son magasin ;
les mutations de configuration/planning/correction manager conservent leurs annotations
de sécurité. Un administrateur peut consulter les magasins mais n'est pas présenté
comme un préparateur capable d'exécuter une mission sans identité d'employé.

La lecture d'un ordre retourne `revision` et `allowedActions`. Une exécution retourne
également la révision de son ordre. Les actions exposées sont des permissions contextualisées ;
les quantités, températures, capacités, appartenances et états sont toujours revérifiés
au moment de confirmer. Une capacité affichée n'est jamais une autorisation côté client.

## Contrat versionné

L'endpoint existant reste `POST /handling-orders/operator/commands`.

```json
{
  "contractVersion": 2,
  "storeId": 1,
  "executionId": 123,
  "requestKey": "une-reference-unique-immuable",
  "expectedRevision": "revision-retournee-par-la-lecture",
  "action": "PICK",
  "itemId": 456,
  "quantity": 2,
  "toteCode": "BAC-01"
}
```

Pour les rapports multiordres, fournir `expectedRevisions`, un objet dont chaque clé
est l'identifiant d'ordre et chaque valeur sa révision. Toutes les révisions sont
vérifiées avant de modifier le métier. Une révision manquante produit 400
`REVISION_REQUIRED`, une révision dépassée 409 `STALE_REVISION`. Il faut alors relire
et demander une nouvelle confirmation humaine avec une nouvelle clé. Ne pas remplacer
silencieusement la révision d'une intention déjà sauvegardée.

Version omise ou 1 : adaptateur historique, mêmes opérations et mêmes résultats utiles
(`orders`, `action`, `requestKey`, `appliedAt`), avec avertissement
`LEGACY_WITHOUT_REVISION`. Les anciens reçus restent rejouables, y compris quand leur
payload a été écrit avant l'ajout des nouveaux champs facultatifs.

Une réussite HTTP 200 contient `contractVersion: 2`, `outcome: CONFIRMED`, `trackingId`,
`facts`, les ordres actualisés, `warnings` et `nextActions`. Les faits précisent l'action,
les quantités et adresses concernées lorsque ces champs s'appliquent. Un volume inconnu
est un avertissement `VOLUME_UNKNOWN`, pas un échec de confirmation.

Les refus métier opérateur sont JSON : `outcome: REJECTED`, `code`, `message`, `trackingId`,
`retryable`, `nextActions`. Le header `X-Tracking-Id` reprend l'identifiant. Les erreurs
403/404, JSON invalide, conflit et indisponibilité technique restent distinctes.
Un refus 401 émis en amont par la couche d’authentification est aussi traduit en
consigne de reconnexion par le transport du picker, même sans corps JSON.
Les détails de persistence/framework restent dans les logs, pas dans les consignes.
Les autres API de l'application conservent leur contrat.

## Rejeu et concurrence

Avant l'envoi, sauvegarder l'enveloppe exacte, sa clé et ses révisions. Une perte réseau
ne prouve pas un échec métier : lire
`GET /handling-orders/operator/commands/{requestKey}?storeId=...` puis, si aucun reçu
n'existe, renvoyer la même enveloppe avec la même clé. Les reçus sont privés au couple
magasin/principal authentifié. Le reçu et l'effet métier sont committés ensemble.

La consultation d'un reçu précède la vérification de révision lors du rejeu : une action
réussie reste acquittée même si la mission a changé depuis. Réutiliser la clé pour un
contenu différent produit 409 `IDEMPOTENCY_KEY_REUSED`. L'ordre des propriétés JSON et
l'échelle équivalente des décimaux ne changent pas l'intention.

Le verrou de magasin existant sérialise les commandes opérateur. Les ordres/exécutions,
articles et bacs physiques sont verrouillés avant leur mutation. La réservation d'un
bac physique est aussi protégée dans la fabrique commune, donc pour les anciens
appelants. Les dépôts utilisent le verrou existant du buffer avant de contrôler la
cellule. Les versions JPA restent un contrôle supplémentaire. Aucun second journal
ni nouvelle table n'est créé : `operator_command_receipts` reste l'unique reçu.
Cette sérialisation privilégie la correction ; sa granularité pourra être mesurée
lors du chantier de performance T57.

## Picker

Le picker joint les révisions connues lors de la création de l'intention, avant sa
sauvegarde locale. Les anciennes intentions déjà enregistrées restent envoyées telles
quelles. Une confirmation incertaine reste en attente ; le transport distingue
`NETWORK_UNCERTAIN` (aucune réponse), erreur HTTP métier et indisponibilité serveur.
Les avertissements métier sont présentés après la confirmation, sans l'annuler.

## Vérifications

```sh
./mvnw -Dtest=OperatorContractTest test
# Dans storepicker, avec JAVA_HOME pointant vers Java 21 :
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 tools/check_http_contract.py --url http://localhost:8040
```

Les tests HTTP utilisent de vrais JWT et des transactions indépendantes. Les cas
concurrents démarrent deux requêtes ensemble, puis vérifient les quantités, les reçus,
les claims de bacs et l'unicité d'occupation des cellules en base. Une capacité dépassée
après mutation vérifie le rollback complet, puis la possibilité de renvoyer la même
intention après correction de la cause.


### Compatibilité des anciennes routes de manutention

Les routes REST historiques `/handling-orders` utilisent le contexte opérateur authentifié : magasin autorisé, compte actif, garde de l’exécution et affectation. Le badge de réservation doit correspondre au compte connecté. Les références d’articles, bacs et buffers sont contrôlées avant mutation ; les lots de requêtes sont atomiques. Une exécution terminée ou affectée à un autre préparateur ne peut pas être modifiée par ces routes.

Les collectes ordinaires, rapports individuels et rapports en bloc enregistrent les mesures de travail dans la transaction de validation. Les prélèvements, substitutions, manquants et dépôts restent des mesures distinctes. Les réémissions des commandes opérateur et récupérations ne créent pas de second bilan. Les gestes historiques sans clé client conservent leur contrat : une opération effectivement confirmée crée son propre acquittement ; les rapports d’exécutions déjà terminées sont refusés.

Le marqueur interne de projection des rapports `VALIDATE_REPORT` / `RECOVER_REPORT` est désormais 2. Un ancien rapport marqué 1 ne constitue pas une preuve suffisante de bilan exhaustif : la synthèse le signale comme historique incomplet, sans inventer ses quantités.


La libération d’une réservation utilise le même contrôle sur les anciennes et nouvelles routes : un inventaire commencé ou une exécution avec travail confirmé ne sont pas supprimables. Les reçus des gestes historiques sont corrélés à leur exécution pour conserver cette protection après retour des articles et détachement des bacs. Un détachement de bac vide conserve sa session clôturée et ses références de traçabilité ; le bac physique est réutilisable. Un retour exige le bac source réel. Le signalement historique d’un manquant alimente le même registre de manquants que les commandes opérateur.
