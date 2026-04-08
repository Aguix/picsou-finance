# Design — GoalCalendarPage : remplacement des anneaux SVG par des donuts

**Statut : VALIDÉ — prêt pour implémentation**

## Contexte

La vue "Grille" de `GoalCalendarPage` (`/goals/:id/calendar`) affiche les mois écoulés sous forme de petits anneaux SVG (52px, stroke 5px) dans une grille 6 colonnes. Le texte intérieur montre uniquement le pourcentage.

L'objectif est de les remplacer par des donuts plus lisibles, avec montant + pourcentage à l'intérieur, dans un layout carte 3 colonnes.

## Fichiers concernés

- **Modifier** : `frontend/src/pages/goals/GoalCalendarPage.tsx`
  - Ajouter helper `formatCompact`
  - Réécrire `YearGridView`
  - Mettre à jour le skeleton de chargement
- **Ne pas toucher** : `TimelineView`, `CalendarGridView`, `MonthDetailPanel`, `GoalsPage`

---

## Décisions de design

### 1. Layout

**Carte année** — une `<Card>` par année (composant shadcn existant) :
- `CardHeader` : label année en uppercase muted (`2024`, `2025`…), identique à l'existant
- `CardContent` : grille 3 colonnes de cartes mois

**Carte mois** — chaque mois = un `<button>` :
- `border`, `border-radius: rounded-xl`, fond légèrement distinct du fond de la carte année
- Structure verticale :
  1. Label mois en haut (`JANV.`, `FÉVR.`…)
  2. Donut SVG au centre avec texte superposé
  3. `obj. XXX` en bas

### 2. Donut SVG

- **Taille** : 80px (`viewBox="0 0 80 80"`, `r=30`)
- **Stroke de base** : 9px
- **Implémentation** : SVG custom (deux `<circle>` superposés), pas recharts
- **Texte** : overlay DOM absolu (`absolute inset-0 flex flex-col items-center justify-center`), pas de `<text>` SVG

### 3. Couleurs de progression

Conserver la logique `getProgressColor` existante :

| Ratio | Couleur | CSS var |
|-------|---------|---------|
| Pas de données | muted | `--muted-foreground` |
| < 60% | rouge | `--destructive` |
| 60–99% | amber | `f59e0b` |
| ≥ 100% | indigo primary | `--primary` |

### 4. Cas overflow (> 100%)

- Cercle de base **plein** en `--primary` (indigo), `stroke-linecap="butt"`
- Arc bonus en **`#818cf8`** (indigo clair), stroke **12px** (légèrement plus large que la base)
- Arc bonus = `min(ratio - 1, 1) × circonférence` → cap à un cercle complet (200% max)
- Si bonus < 100% → `stroke-linecap="round"` (extrémités arrondies)
- Si bonus = 100% → `stroke-linecap="butt"` (cercle parfait, pas de bump)
- Le pourcentage affiché passe à `+X%` (ex. `+10%`, `+100%`)
- Au-delà de 200% total : même rendu visuel que 200%, le vrai % reste dans le texte

### 5. Texte intérieur — taille de police

- Montant : **11px bold**
- Pourcentage : **9px**
- Gap entre les deux lignes : **2px**
- Valeur nulle : `–` seul, 13px, centré, couleur muted

### 6. Formatage du montant (`formatCompact`)

```
< 1 000 €   → standard sans décimales      "475 €", "999 €"
≥ 1 000 €   → compact, 1 décimale          "1 k€", "1,5 k€", "9,9 k€"
≥ 10 000 €  → compact, 0 décimale          "10 k€", "200 k€"
≥ 1 000 000 € → compact, 2 décimales       "1,5 M€"
null         → "–"
```

Résultat max ~6 caractères dans tous les cas → pas de débordement dans le cercle.

### 7. Indicateurs override / saisie manuelle

- Point **9px** en `top: 3px; right: 3px`, `border: 2px solid background`
- Violet (`#7c3aed`) = objectif du mois modifié manuellement (`entry.override != null`)
- Bleu (`#3b82f6`) = contribution déclarée manuellement (`entry.manualActual != null`)
- Le point bleu a priorité si les deux sont présents

### 8. État sélectionné

- Mois sélectionné : `border-primary bg-accent`
- Mois non sélectionné : `border-border hover:bg-accent/50`

### 9. Skeleton de chargement

Mettre à jour le skeleton existant pour correspondre au nouveau layout :
- Grille 3 colonnes
- Chaque cellule : border + border-radius, avec 3 skeletons (label / cercle 80px / obj label)

---

## Logique de rendu par cas

| Situation | Arc base | Arc bonus | Texte ligne 1 | Texte ligne 2 |
|-----------|----------|-----------|---------------|---------------|
| Pas de données | muted, vide | aucun | `–` (centré, muted) | — |
| 0% | destructive, vide | aucun | `0 €` | `0%` |
| 30% | destructive, 30% | aucun | montant | `30%` |
| 65% | amber, 65% | aucun | montant | `65%` |
| 95% | primary, 95% | aucun | montant | `95%` |
| 100% | primary, plein, butt | aucun | montant | `100%` |
| 110% | primary, plein, butt | indigo clair 10%, round | montant | `+10%` |
| 200% | primary, plein, butt | indigo clair plein, butt | montant | `+100%` |
| 400% | primary, plein, butt | indigo clair plein, butt | montant | `+100%` (cap) |
