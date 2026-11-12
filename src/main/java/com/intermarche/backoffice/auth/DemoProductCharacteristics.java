package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductType;
import java.util.Map;

/** Descriptive demo copy, not manufacturer-certified ingredients or dietary claims. */
final class DemoProductCharacteristics {
    private DemoProductCharacteristics() {}
    private static final Map<String,String> TEXTS=Map.ofEntries(
        Map.entry("3017620422003","Épicerie sucrée. Pâte à tartiner pour les tartines du petit-déjeuner et du goûter. Texture à étaler ; utilisation possible en pâtisserie."),
        Map.entry("3274080005003","Produit laitier. Lait demi-écrémé à boire, pour les céréales, les boissons chaudes et la cuisine. Se distingue du lait entier par son niveau d'écrémage."),
        Map.entry("3560070057003","Épicerie salée. Pâtes sèches de forme penne, à cuire dans l'eau. Accompagnement de repas, sauces et gratins ; forme courte tubulaire."),
        Map.entry("3245412630003","Fruit frais. Pommes de variété Golden, à manger ou à cuisiner en compote et en tarte. Vente au poids ; fruits entiers."),
        Map.entry("8712100325003","Entretien du linge. Lessive sous forme liquide pour laver les vêtements. Produit ménager non alimentaire, distinct d'un adoucissant et d'un produit vaisselle."),
        Map.entry("3017640123454","Boisson chaude. Café déjà moulu pour préparer une boisson caféinée. La mouture le distingue du café en grains, qui nécessite un moulin."),
        Map.entry("3274080012346","Épicerie salée. Riz de variété basmati à cuire, en accompagnement de plats. Céréale en grains ; ne se confond pas avec des pâtes ou de la semoule."),
        Map.entry("3017650987657","Épicerie salée. Huile d'olive liquide pour l'assaisonnement et la cuisine. Matière grasse végétale, distincte du beurre et des autres huiles."),
        Map.entry("3012345678902","Épicerie sucrée. Sucre en poudre pour sucrer les boissons et préparer des desserts. Présentation en poudre, distincte des morceaux et du sucre glace."),
        Map.entry("3012345098762","Aide à la pâtisserie. Farine de type T55 pour la cuisine et les préparations boulangères. Poudre à incorporer aux recettes."),
        Map.entry("3274080076546","Boisson aux fruits. Jus d'orange à boire au petit-déjeuner ou en accompagnement d'un repas. Saveur orange ; teneur en pulpe et sucres ajoutés non renseignés."),
        Map.entry("3274080087658","Produit laitier. Yaourts nature présentés en lot de huit. Dessert ou collation à la cuillère ; se distinguent des yaourts aromatisés aux fruits."),
        Map.entry("3274080098760","Produit laitier. Beurre demi-sel pour les tartines, la cuisson et la pâtisserie. Matière grasse sous forme solide ; caractère demi-sel distinct du beurre doux."),
        Map.entry("3274080010984","Œufs frais présentés en boîte de douze. Pour la cuisine, les omelettes et la pâtisserie. Calibre et mode d'élevage non renseignés."),
        Map.entry("3245412631009","Légume-fruit frais. Tomates de forme ronde pour les salades et les plats cuisinés. Vente au poids ; variété et maturité non renseignées."),
        Map.entry("3245412632006","Fruit frais. Bananes entières à éplucher, pour une collation, les desserts et les préparations fruitées. Vente au poids."),
        Map.entry("3274080021096","Poisson préparé. Saumon fumé pour les entrées, les tartines et les salades. Le fumage le distingue du saumon frais à cuire."),
        Map.entry("3012345556668","Boulangerie emballée. Pain de mie complet pour les tartines et les sandwichs. Texture souple ; caractère complet indiqué dans la dénomination."),
        Map.entry("8712100326004","Hygiène capillaire. Shampoing pour laver les cheveux, à rincer. Type de cheveux et formule spécifique non renseignés."),
        Map.entry("8712100327001","Hygiène domestique. Papier toilette présenté en lot de douze rouleaux. Nombre de plis, parfum et longueur des rouleaux non renseignés."),
        Map.entry("3017620421006","Épicerie sucrée. Confiture de fraises à tartiner ou à utiliser dans des desserts. Saveur fraise ; proportion de fruits et teneur en sucres non renseignées."),
        Map.entry("3017620421013","Épicerie sucrée. Miel d'acacia pour les tartines, les boissons et les desserts. Origine géographique et texture non renseignées."),
        Map.entry("3017620421020","Confiserie et pâtisserie. Chocolat noir à 70 %, pour la dégustation et les recettes chocolatées. Se distingue du chocolat au lait et du chocolat blanc."),
        Map.entry("3017620421037","Boisson chaude. Thé vert présenté en vingt sachets à infuser. Variété précise et éventuels arômes non renseignés."),
        Map.entry("3017620421044","Boisson chaude. Café en grains à moudre avant extraction. Convient à une préparation disposant d'un broyeur ou d'un moulin ; distinct du café déjà moulu."),
        Map.entry("3017620421051","Épicerie salée. Semoule fine pour des préparations culinaires et des desserts. Granulométrie fine, distincte d'une semoule à gros grains."),
        Map.entry("3017620421068","Épicerie salée. Lentilles vertes, légumineuses pour les accompagnements, salades et plats cuisinés. Mode de préparation à vérifier sur l'emballage."),
        Map.entry("3017620421075","Épicerie salée. Haricots rouges pour les salades et les plats mijotés. Légumineuses ; état sec ou précuit non renseigné."),
        Map.entry("3017620421082","Épicerie salée. Maïs doux pour les salades et les accompagnements. Présentation et poids égoutté non renseignés."),
        Map.entry("3017620421099","Poisson préparé. Thon au naturel pour les salades, sandwichs et plats froids. Préparation au naturel, distincte du thon à l'huile."),
        Map.entry("3017620421105","Poisson préparé. Sardines à l'huile pour les entrées et les tartines. Espèce et préparation distinctes du thon au naturel."),
        Map.entry("3017620421112","Dessert fruitier. Compote de pommes présentée en quatre portions. Texture de compote ; présence de sucres ajoutés non renseignée."),
        Map.entry("3017620421129","Fromage. Camembert à consommer en plateau ou dans des préparations chaudes. Type de lait et traitement thermique non renseignés."),
        Map.entry("3017620421136","Produit laitier. Yaourts à la fraise présentés en lot de huit. Dessert à la cuillère ; saveur fraise distincte du yaourt nature."),
        Map.entry("3017620421143","Produit laitier. Lait entier pour les boissons, les céréales et la cuisine. Niveau d'écrémage entier, distinct du lait demi-écrémé et écrémé."),
        Map.entry("3017620421150","Produit laitier. Crème fraîche pour les sauces, les plats cuisinés et les desserts. Teneur en matière grasse et texture épaisse ou liquide non renseignées."),
        Map.entry("3017620421167","Charcuterie. Jambon blanc présenté en quatre tranches, pour les sandwichs et les repas. Composition détaillée et teneur en sel non renseignées."),
        Map.entry("3017620421174","Traiteur. Poulet rôti pour un repas ou une préparation froide. Volaille déjà rôtie, distincte d'une volaille crue à cuire."),
        Map.entry("3017620421181","Poisson frais. Deux pavés de saumon pour une préparation culinaire. Vente au poids ; produit frais distinct du saumon fumé."),
        Map.entry("3017620421198","Fromage. Mozzarella pour les salades, pizzas et préparations cuisinées. Type de lait et conditionnement précis non renseignés."),
        Map.entry("3017620421204","Boisson aux fruits. Jus de pomme à boire au petit-déjeuner ou au cours d'un repas. Saveur pomme ; clarification et sucres ajoutés non renseignés."),
        Map.entry("3017620421211","Fromage déjà râpé pour les pâtes, les gratins et les préparations chaudes. Variété du fromage et composition du mélange non renseignées."),
        Map.entry("3017620421228","Préparation végétale. Tofu nature pour les plats cuisinés et les préparations à assaisonner. Saveur nature ; composition et labels à vérifier."),
        Map.entry("3017620421235","Hygiène bucco-dentaire. Dentifrice pour le brossage des dents. Teneur en fluor, parfum et public cible non renseignés."),
        Map.entry("3017620421242","Hygiène corporelle. Savon liquide pour le lavage. Formule, parfum et propriétés spécifiques non renseignés."),
        Map.entry("3017620421259","Hygiène corporelle. Gel douche pour le lavage du corps, à rincer. Parfum et type de peau non renseignés."),
        Map.entry("3017620421266","Hygiène corporelle. Déodorant présenté en spray. Produit sans rinçage ; parfum et présence d'une fonction antitranspirante non renseignés."),
        Map.entry("3017620421273","Hygiène quotidienne. Mouchoirs présentés par cent unités. Nombre de plis, parfum et présentation des paquets non renseignés."),
        Map.entry("3017620421280","Entretien de la maison. Sacs poubelle de capacité 50 litres, présentés par vingt unités. Pour la collecte des déchets ; type de fermeture non renseigné."),
        Map.entry("3017620421297","Entretien de la maison. Éponges présentées par trois unités pour le nettoyage. Matière et présence d'une face abrasive non renseignées."),
        Map.entry("3017620422101","Légume préparé surgelé. Frites de pommes de terre pour accompagner un repas. Mode de cuisson et présence d'assaisonnement non renseignés."),
        Map.entry("3017620422118","Plat préparé surgelé. Pizza aux quatre fromages pour un repas après préparation. Garniture fromagère ; composition détaillée non renseignée."),
        Map.entry("3017620422125","Légume surgelé. Petits pois extra-fins pour les accompagnements et les plats cuisinés. Calibre extra-fin indiqué dans la dénomination."),
        Map.entry("3017620422132","Dessert glacé à la vanille. Pour la consommation en dessert ; saveur vanille, distincte des autres parfums de glace."),
        Map.entry("3017620422149","Poisson préparé surgelé avec panure. Pour un repas après préparation ; espèce du poisson et mode de cuisson non renseignés."),
        Map.entry("3017620422156","Volaille préparée surgelée. Nuggets de poulet pour un repas après préparation. Composition et type de panure non renseignés."),
        Map.entry("3017620422163","Légume surgelé. Épinards déjà hachés pour les accompagnements et les préparations cuisinées. Se distinguent des épinards en feuilles."),
        Map.entry("3017620422170","Viennoiserie surgelée. Croissants pur beurre présentés par quatre unités, pour le petit-déjeuner ou le goûter. Préparation à vérifier sur l'emballage.")
    );

    static void populate(Product product,String originalEan) {
        if(product.characteristics!=null && !product.characteristics.isBlank())return;
        String description=TEXTS.get(originalEan);
        if(description==null)return;
        StringBuilder text=new StringBuilder(product.name).append(". ").append(description);
        if(product.brand!=null)text.append(" Marque : ").append(product.brand).append('.');
        text.append(product.productType==ProductType.WEIGHT?" Vendu au poids, en kilogrammes.":product.productType==ProductType.VOLUME?" Vendu au volume, en litres.":" Vendu à la pièce ou au lot indiqué dans la dénomination.");
        if(product.referenceWeight!=null)text.append(" Poids de référence : ").append(product.referenceWeight.stripTrailingZeros().toPlainString()).append(" kg.");
        if(product.referenceVolume!=null)text.append(" Volume de référence : ").append(product.referenceVolume.stripTrailingZeros().toPlainString()).append(" L.");
        text.append(" Circuit logistique de la démo : ").append(switch(product.temperatureZone){case CHILLED->"frais";case FROZEN->"surgelé";case HOUSEHOLD->"entretien";default->"ambiant";}).append('.');
        text.append(" Données descriptives de démonstration ; ingrédients, allergènes et certifications non validés.");
        product.characteristics=text.toString();
    }
}
