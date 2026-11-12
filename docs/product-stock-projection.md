# Produits et stocks (T10)

`stockPage` parcourt les produits actifs avec un curseur d'identifiant produit.
Le compte de stock est joint pour le magasin autorisé, sans création à la lecture.
Un produit sans compte utilise `stockKnown=false`, `accountId=null`, des quantités
nulles et la situation UNKNOWN (« Non renseigné »). Un compte connu à zéro reste
UNAVAILABLE (« Indisponible »). Les identifiants produit stabilisent sélection,
glisser-déposer et détails multiples, indépendamment de l'existence du compte.

Une page contient au plus 50 produits. Les situations calculées nécessitant un
filtrage peuvent examiner au plus 200 candidats par requête. Les filtres de
magasin, bâtiment, zone, allée, baie, buffer et produit restent côté serveur.

La projection groupe les lectures des marges, mouvements, approvisionnements,
engagements et adresses. Les données du journal sont scalaires : aucune relation
de bac n'est chargée implicitement pour calculer la disponibilité. Le calcul de
chaque chronologie se fait ensuite en mémoire sous le même verrou magasin que
les écrivains. Les réservations concurrentes restent contrôlées.

Le test HTTP sur 40 000 références compte les requêtes pour 1/10/30/50 produits,
y compris avec transferts et engagements. Il refuse un nombre de requêtes qui
augmente avec la taille de page et impose un budget de 15 requêtes au scénario.
Les mesures détaillées se trouvent dans `picker-suivi/preuves-t10`.

Toute requête dépassant 100 ms mérite analyse. Le journal Hibernate signale les
instructions SQL dépassant 100 ms ; les paramètres liés ne sont pas journalisés.
Le benchmark conserve aussi les requêtes JPQL dépassant ce seuil selon les
statistiques Hibernate. Ces mesures sont distinctes de la durée HTTP et du rendu
navigateur. Les mesures locales H2, avec un client, ne constituent pas un test de
capacité multi-utilisateur.
