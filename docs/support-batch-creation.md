# Création en série des bacs et chariots

Dans Bacs et chariots, « Créer en série » ouvre une modale avec le choix bacs/chariots, type, modèle d'identifiant, premier numéro, nombre et volume facultatif si défini sur le type. Les chariots reçoivent tous la grille rangées × colonnes choisie.

Exemple : BAC-{0001}, premier numéro 51, nombre 3 → BAC-0051, BAC-0052, BAC-0053. Le compteur contient des zéros et se termine par 1 ; un seul compteur est autorisé. Identifiants alphanumériques, tirets et soulignements, 20 caractères maximum. Série limitée à 500 éléments.

L'aperçu ne crée rien. Toute collision avec un bac historique ou un matériel existant bloque la série entière ; aucun existant n'est modifié. Le serveur valide à nouveau lors de la confirmation, dans une transaction, avec reçu de commande et relance idempotente. Accès administrateur/manager, limité au magasin autorisé.

API : POST /field-operations/supports/batch/preview et POST /field-operations/supports/batch, avec storeId, typeId, pattern, start, count, volumeLiters et, pour les chariots, rows/columns. La création requiert requestKey.

Validation : scénario Java d'aperçu, création, répétition, conflit sans création partielle, restriction de rôle et format invalide ; 11 tests d'interface ciblés. Vérification dans StorePilot du rendu, de la configuration des chariots, des collisions réelles CH-01 à CH-04 et du bouton désactivé.

# Jeu de missions de Marie Martin

Badge 100235. Le seed ajoute une seule fois les types manquants, conserve sa collecte par zone en cours et libère les doublons planifiés de réapprovisionnement. Les anciens ordres terminés restent dans l'historique. Dix missions actives, une par catégorie : collecte produit, collecte zone, packing, regroupement, inventaire, traitement après annulation, déchargement, réachalandage, transfert et mise en sécurité. Les lots et mouvements prévisionnels sont réels, sans inventer de prélèvement effectué.

Le test DemoMarieMissionsTest vérifie les dix catégories, l'unicité et le maintien des mêmes identifiants après répétition du seed. La liste courante a aussi été contrôlée par l'API avec le compte de Marie.
