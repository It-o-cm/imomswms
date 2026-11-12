# Coordination des missions — T21 à T25

Les commandes sont limitées au magasin authentifié. Le verrou magasin et la révision observée protègent les décisions concurrentes. Les changements de ligne, propositions, annulations et traitements disposent de reçus de rejeu ; les gestes physiques existants gardent leur journal.

## Lignes et consignes

`GET/PUT /field-operations/orders/{id}/lines` utilise l’identité stable `LOT_LINE` ou `EXPECTED_SUPPLY`. Différer ne supprime ni la ligne, ni ses prélèvements. Le parcours Java exclut les étapes différées de la tournée active et les fournit séparément dans `deferred` ; la fiche imprimable les indique comme à reprendre. Les baies prioritaires passent avant les baies ordinaires, avec optimisation de distance à priorité égale. Les lignes d’une même baie restent regroupées.

Le responsable publie priorité et texte. Le préparateur peut différer/reprendre ses lignes et acquitter une version précise. Seule une modification du texte change `instructionVersion` ; modifier la priorité conserve les accusés.

## Transmission

Le responsable ou le préparateur affecté propose une transmission à un préparateur actif du magasin. Présence, absences, conflits et état opérationnel sont contrôlés à la proposition puis à l’acceptation. Si les bâtiments déclarés diffèrent, le trajet configuré définit `readyAt`. L’offre ne libère pas le préparateur source.

Seul le destinataire accepte. Sous verrou, le serveur vérifie encore la révision, transfère l’affectation et toutes les exécutions actives, conserve les bacs et cellules, puis ferme les offres concurrentes. Les traitements d’annulation encore liés suivent la même responsabilité, ou bloquent la transmission si un autre préparateur en a déjà la garde. Un ancien reçu reste consultable ; l’ancien préparateur ne peut plus confirmer un nouveau geste.

## Annulation et produits déjà prélevés

Depuis le détail de commande, le responsable choisit les lignes à annuler et une politique proposée : retour, réaffectation ou isolement. L’API `POST /field-operations/customer-orders/{id}/cancel` crée des ordres `CancellationDispositionOrder`, visibles dans Manutention et le picker. Les groupes respectent la mission qui détient réellement le bac et le bâtiment de prise en charge. Leurs codes sont alloués par un compteur global, car la contrainte d’unicité existante dépasse le magasin.

Les produits déjà en bac restent physiquement présents. Une réservation de traitement les maintient indisponibles jusqu’au retour ou à la réaffectation ; elle reste active après isolement. Le retour à l’emplacement d’origine conserve la quantité historique. La réaffectation satisfait un besoin réel de même produit et de même quantité dans un bac compatible, avec contrôle de capacité. L’isolement utilise un support vide distinct et un dépôt explicite en buffer/armoire/cellule. Les autres produits d’un bac mélangé ne sont pas retirés. Les lignes annulées sont exclues des préparations suivantes et bloquées à l’expédition.

Le retour ne fabrique pas un second mouvement d’entrée : le prélèvement historique standard ne débite pas encore le journal temporel au scan. L’unification des mouvements et segments de stock est traitée par T30–T31 ; ce raccord doit préserver ces compensations.

## Planning

La tuile Équipe montre états opérationnels et transmissions en attente. Une pause n’est pas transformée en absence, ni en créneau implicitement libéré. Le solveur conserve les missions suspendues et refuse de nouvelles opérations mobiles aux employés en pause/fin de poste. La révision de prévisualisation couvre également ces états et les transmissions. Une mission déjà démarrée change de préparateur par transmission acceptée, pas par modification directe du planning.

Positions et exécutions sont chargées en groupes dans le réordonnancement. Aucun suivi GPS ni déclaration obligatoire de position n’est ajouté.

## Supports

`SupportType` définit TOTE, CARTON, PALLET et CART, les conditionnements autorisés et les capacités connues. `PhysicalSupport` référence explicitement le `Tote` historique : son identifiant, contenu, volume individuel et affectation sont conservés. AVAILABLE/BLOCKED/RETIRED sont des états de service ; IN_USE est déduit de la session de chargement.

La migration V6 associe les bacs présents sans les déplacer. L’attachement reprend `ADD_TOTE`, son autorisation, sa révision et son reçu. Les supports bloqués, retirés, étrangers ou incompatibles sont refusés. Poids et volumes inconnus restent signalés comme incomplets. Les chariots à compartiments et l’interface de gestion des supports relèvent de T26–T28.
