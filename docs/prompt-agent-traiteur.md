# Campagne de réalisation — Traiteur & réceptions dans imomswms

Tu réalises, de bout en bout et sans supervision, la prise en charge des commandes traiteur par imomswms. Le travail est décrit par une spécification et découpé en 453 points. Tu avances par paquets jusqu'à ce que les 453 points soient terminés, testés et prouvés. Personne ne suit ton travail en direct : ce que tu laisses dans le dépôt (code, tests, fichiers de suivi) est la seule trace de ce qui a été fait.

## Contexte

imomswms est le serveur de gestion de magasin (Quarkus 3.30, Java 21, H2, Flyway, port 8040). Il reçoit les commandes du site marchand immcomm et organise leur préparation. Aujourd'hui il ne sait préparer que des courses : une commande traiteur (pain surprise, menu à composer, gâteau personnalisé) arrive sans sa composition et ne peut pas être fabriquée. La spécification décrit comment le magasin reçoit la composition, planifie la fabrication, la fait exécuter sur le terminal de l'opérateur, trace et étiquette le produit, puis le remet avec les courses.

C'est une application de démonstration et elle le restera. La base est supprimée et régénérée à chaque `launch.sh`. N'ajoute aucun réflexe de production : pas de conservation de données, pas de migration prudente d'un existant, pas de durcissement, pas de saisie de secrets.

## Documents qui font autorité

Dans `/Users/henridarmet/Documents/Workspaces/imomswms/docs/` :

- `spec-traiteur-v1.md` — la spécification. C'est elle qui dit ce que le code doit faire : principes P1 à P8, règles de réception I1 à I9, règles serveur F1 à F23, contrats du § 8, paramètres du § 6, démonstration du § 10, tests du § 11.
- `plan-realisation-traiteur-v1.md` — les 453 points, en 9 lots T0 à T8. Chaque point renvoie à la spécification. Le plan dit quoi réaliser et dans quel ordre ; la spécification dit comment. En cas de désaccord entre les deux, la spécification l'emporte.
- `resume-traiteur.pdf` — le principe en cinq pages, à lire d'abord pour comprendre l'intention.

Lis la spécification en entier avant d'écrire la première ligne. Relis les règles concernées au début de chaque paquet : beaucoup de points n'ont de sens qu'avec la règle complète sous les yeux.

## Périmètre

- Lots T0 à T7 : dans `/Users/henridarmet/Documents/Workspaces/imomswms`.
- Lot T8 : dans `/Users/henridarmet/Documents/Workspaces/immcomm` (l'émetteur des commandes). N'y touche qu'à ce que T8 demande.
- Storepilot et Storepicker ne sont pas dans cette campagne : ne les modifie pas. Le lot T6 fixe ce que le serveur leur sert ; leurs écrans feront l'objet d'une autre spécification.

Reste strictement dans ce que les points demandent. Le code existant que tu traverses ne se refactore pas, ne se renomme pas et ne s'améliore pas, même si tu vois mieux à faire : chaque changement hors périmètre est un risque de régression que personne n'a demandé. Deux exceptions. Les adaptations de l'existant que la spécification nomme (points de nature « Adaptation ») sont dans le périmètre. Et un défaut se corrige toujours : si tu rencontres un bogue ou un test en échec, même antérieur à ton travail, tu le corriges et tu le notes dans le suivi, tu ne le signales pas en le laissant en l'état.

## Façon de travailler

Avance par paquets de 20 à 30 points qui forment un ensemble cohérent : une règle avec ses contrats et ses tests, pas une tranche arbitraire de numéros. Suis l'ordre du plan. Un lot ne commence que lorsque les lots dont il dépend sont terminés ; à l'intérieur d'un lot, les points sont déjà dans l'ordre de réalisation.

Pour chaque paquet :

1. Relis les règles de la spécification visées par ses points, et le code existant qu'elles touchent.
2. Réalise les points, tests compris.
3. Lance la suite complète du dépôt concerné. Elle doit passer entièrement.
4. Écris les preuves et mets à jour le suivi (voir plus bas).
5. Commite.

Ne pose pas de question et n'attends pas de validation : personne ne répondra. Quand un point laisse un choix de réalisation, prends le plus simple qui respecte la règle et note-le dans le suivi. Les choix métier ne sont jamais à trancher par toi : dans cette spécification, toute valeur métier est un paramètre avec sa valeur de démonstration (§ 6).

## Règles de code

Elles ne souffrent aucune exception, et leur oubli fait rejeter le travail :

- Code et commentaires en anglais. Les libellés servis aux utilisateurs restent en français, comme dans la spécification.
- Javadoc sur toutes les méthodes Java, nouvelles ou modifiées : constructeurs, accesseurs, méthodes privées, méthodes redéfinies et callbacks compris.
- Paquets : `domain/catering` et `engine/catering` pour les classes nouvelles, sauf celles qui rejoignent une famille existante dans son paquet (point T0.002). Jamais de paquet à classe unique.
- L'heure se lit par `DateTimeProvider`, jamais par `LocalDateTime.now()`.
- Le serveur calcule, les clients affichent : aucune date, durée, charge ou montant laissé au calcul d'un écran.
- Rien n'est planifié ni affecté sans validation du manager, sauf le paramètre `autoValidateProposals`.
- Schéma : une seule migration nouvelle, `V30__catering.sql`. Ne modifie jamais une migration existante.
- N'ajoute pas de dépendance Maven sans nécessité démontrée.

## Tests

- Chaque jambe de chaque condition composée est couverte : pour `a || b || c`, un cas par jambe vraie et un cas où tout est faux ; pour `a && b`, un cas par jambe fausse. Ne te contente pas des deux bras du `if`. Vérifie-le avec le rapport JaCoCo du projet avant de clore un paquet, classe par classe, sur le code que tu as écrit.
- Les suites existantes passent sans que tu les modifies. Une charge de commande sans les blocs ajoutés doit se comporter comme avant, aux deux exceptions voulues I4 et I8. Si un test existant casse, c'est ton code qui est en cause dans presque tous les cas : corrige le code, pas le test. Ne modifie un test existant que s'il affirme un comportement que I4 ou I8 change délibérément, et note-le.
- Les tests portent les noms de classes du § 11.1.
- Un test qui ne vérifie rien ne compte pas : chaque cas affirme le résultat métier attendu par la règle, pas seulement l'absence d'erreur.

## Preuves et suivi

Tiens le suivi dans `/Users/henridarmet/Documents/Workspaces/imomswms/docs/traiteur/`, sur le modèle de la campagne traiteur d'immcomm (`immcomm/docs/traiteur/suivi-realisation.json`, `reprise.json`, `preuves-paquet-NN/`) :

- `suivi-realisation.json` : `authority` (le plan), `total` (453), `done`, `remaining`, `currentBatch`, et `points` — pour chacun `id`, `description`, `status` (`A_FAIRE`, `EN_COURS`, `TERMINE`), `evidence`. Au premier lancement, crée-le à partir du plan et vérifie que tu obtiens bien 453 points.
- `preuves-paquet-NN/verification.json` : pour chaque point du paquet, les fichiers créés ou modifiés, les tests qui le couvrent, et le résultat de la suite complète (nombre de tests, échecs, erreurs).
- `reprise.json` : l'état pour reprendre après une interruption — dernier paquet terminé, paquet en cours, résultat de la dernière suite complète, écarts ouverts.
- `ecarts.json` : chaque écart entre la spécification et ce que tu as dû faire (voir ci-dessous), chaque défaut antérieur corrigé, chaque test existant modifié.

Un point n'est `TERMINE` que s'il est réalisé, couvert par ses tests, et que la suite complète passe. Un point réalisé à moitié reste `EN_COURS`. Ne marque jamais un point terminé sur la foi d'une lecture du code : la preuve est un test qui passe ou, pour la recette, un appel réel. Les comptes du suivi doivent toujours être exacts ; ils sont lus tels quels.

Au début de chaque session, lis `reprise.json` et `suivi-realisation.json` et repars de là.

## Quand la spécification et le code ne s'accordent pas

La spécification s'appuie sur des mécanismes existants d'imomswms (répartition des commandes, stock daté, traçabilité, impression, buffers, annulation, supervision). Ils ont été vérifiés dans le code, mais pas ligne à ligne : tu trouveras des endroits où le mécanisme ne se comporte pas exactement comme la spécification le suppose.

Dans ce cas, ne contourne pas en silence et n'abandonne pas le point. Réalise l'intention de la règle par le chemin qui fonctionne réellement, inscris l'écart dans `ecarts.json` (règle, ce que la spécification supposait, ce que le code fait, ce que tu as fait), et corrige le passage correspondant de `spec-traiteur-v1.md` pour qu'il reste vrai. Si deux règles de la spécification se contredisent, applique le principe (P1 à P8) qui les départage et note-le de la même façon.

## Git

Commite à la fin de chaque paquet, dans le dépôt concerné, avec un message qui cite le numéro du paquet et les points clos. Ne pousse jamais. Ne crée pas de branche, n'en change pas et ne réorganise pas l'historique : le dépôt et ses branches sont gérés par leur propriétaire.

## Démonstration et recette

Le lot T7 se termine par une vérification réelle, pas seulement par des tests :

- Lance `./launch.sh --back` dans imomswms et attends que le serveur réponde sur le port 8040. La base est régénérée, datée du jour.
- Vérifie que les quatre commandes `DEMO-TRA-001` à `DEMO-TRA-004` sont dans l'état annoncé au § 10, et que Marie Martin (badge 100235) a un ordre de fabrication qui lui est affecté. Cette exigence a déjà été oubliée dans d'autres campagnes : contrôle-la explicitement.
- Déroule les dix étapes du § 11.2 par de vrais appels aux contrats (les gestes d'écran se font par les routes que ces écrans appelleront). Consigne chaque appel et sa réponse dans les preuves du paquet.
- Recommence le lancement à une autre heure simulée ou un autre jour de la semaine si tu peux le faire sans modifier le code : les données de démonstration doivent tenir quels que soient le jour et l'heure.

## Fin de campagne

La campagne est finie quand les 453 points sont `TERMINE`, que les suites complètes d'imomswms et d'immcomm passent, que la recette a été déroulée sur une instance lancée, et que `ecarts.json` est à jour. Écris alors `docs/traiteur/verification-finale.json` : comptes par lot, résultat des deux suites, résultat de la recette étape par étape, liste des écarts.

Si tu es bloqué sur un point malgré tout (une dépendance extérieure absente, par exemple), laisse-le `EN_COURS` avec la raison précise, continue sur les points qui n'en dépendent pas, et ne déclare pas la campagne finie.

## Compte rendu de fin de paquet

Termine chaque paquet par un compte rendu court, en français :

- numéro du paquet et points clos ;
- avancement : points terminés sur 453, avec le pourcentage, et le détail par lot ;
- résultat de la suite complète : nombre de tests, échecs, erreurs ;
- écarts, défauts antérieurs corrigés et tests existants modifiés dans ce paquet, ou « aucun » ;
- points restés `EN_COURS` et pourquoi.

Dis ce qui est vérifié et ce qui ne l'est pas, sans arrondir : un compte rendu qui annonce terminé ce qui est seulement écrit fait perdre plus de temps qu'un paquet inachevé.
