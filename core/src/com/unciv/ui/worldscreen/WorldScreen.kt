package com.unciv.ui.worldscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.UnCivGame
import com.unciv.logic.GameSaver
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.diplomacy.DiplomaticIncident
import com.unciv.logic.civilization.diplomacy.DiplomaticIncidentType
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.gamebasics.tile.ResourceType
import com.unciv.models.gamebasics.tr
import com.unciv.models.gamebasics.unit.UnitType
import com.unciv.ui.VictoryScreen
import com.unciv.ui.pickerscreens.GreatPersonPickerScreen
import com.unciv.ui.pickerscreens.PolicyPickerScreen
import com.unciv.ui.pickerscreens.TechButton
import com.unciv.ui.pickerscreens.TechPickerScreen
import com.unciv.ui.trade.DiplomacyScreen
import com.unciv.ui.utils.*
import com.unciv.ui.worldscreen.bottombar.WorldScreenBottomBar
import com.unciv.ui.worldscreen.optionstable.PopupTable
import com.unciv.ui.worldscreen.unit.UnitActionsTable

class WorldScreen : CameraStageBaseScreen() {
    val gameInfo = game.gameInfo
    internal val currentPlayerCiv: CivilizationInfo = gameInfo.getCurrentPlayerCivilization()

    val tileMapHolder: TileMapHolder  = TileMapHolder(this, gameInfo.tileMap)
    val minimapWrapper = MinimapHolder(tileMapHolder)

    private val topBar = WorldScreenTopBar(this)
    val bottomBar = WorldScreenBottomBar(this)
    val unitActionsTable = UnitActionsTable(this)

    private val techButton = Table()
    private val diplomacyButtonWrapper = Table()
    private val nextTurnButton = createNextTurnButton()

    private val notificationsScroll: NotificationsScroll

    init {
        topBar.setPosition(0f, stage.height - topBar.height)
        topBar.width = stage.width

        nextTurnButton.setPosition(stage.width - nextTurnButton.width - 10f,
                topBar.y - nextTurnButton.height - 10f)
        notificationsScroll = NotificationsScroll(this)
        notificationsScroll.width = stage.width/3

        minimapWrapper.x = stage.width - minimapWrapper.width

        tileMapHolder.addTiles()

        techButton.touchable=Touchable.enabled
        techButton.onClick("paper") {
            game.screen = TechPickerScreen(currentPlayerCiv)
        }

        stage.addActor(tileMapHolder)
        stage.addActor(minimapWrapper)
        stage.addActor(topBar)
        stage.addActor(nextTurnButton)
        stage.addActor(techButton)
        stage.addActor(notificationsScroll)


        diplomacyButtonWrapper.defaults().pad(5f)
        stage.addActor(diplomacyButtonWrapper)

        bottomBar.width = stage.width
        stage.addActor(bottomBar)
        stage.addActor(unitActionsTable)

        displayTutorials("NewGame")
        displayTutorials("TileLayout")

        createNextTurnButton() // needs civ table to be positioned

        val tileToCenterOn: Vector2 =
                when {
                    currentPlayerCiv.cities.isNotEmpty() -> currentPlayerCiv.getCapital().location
                    currentPlayerCiv.getCivUnits().isNotEmpty() -> currentPlayerCiv.getCivUnits().first().getTile().position
                    else -> Vector2.Zero
                }
        tileMapHolder.setCenterPosition(tileToCenterOn)
    }

    // This is private so that we will set the shouldUpdate to true instead.
    // That way, not only do we save a lot of unneccesary updates, we also ensure that all updates are called from the main GL thread
    // and we don't get any silly concurrency problems!
    private fun update() {
        // many of the display functions will be called with the game clone and not the actual game,
        // because that's guaranteed to stay the exact same and so we won't get any concurrent modification exceptions

        val gameClone = gameInfo.clone()
        gameClone.setTransients()
        val cloneCivilization = gameClone.getCurrentPlayerCivilization()
        kotlin.concurrent.thread {
            currentPlayerCiv.happiness = gameClone.getCurrentPlayerCivilization().getHappinessForNextTurn().values.sum().toInt()
            gameInfo.civilizations.forEach { it.setCitiesConnectedToCapitalTransients() }
        }

        if(bottomBar.unitTable.selectedUnit!=null){
            displayTutorials("UnitSelected")
        }

        if(UnCivGame.Current.settings.hasCrashedRecently){
            displayTutorials("GameCrashed")
            UnCivGame.Current.settings.tutorialsShown.remove("GameCrashed")
            UnCivGame.Current.settings.hasCrashedRecently=false
            UnCivGame.Current.settings.save()
        }

        if (UnCivGame.Current.settings.tutorialsShown.contains("CityEntered")) {
            displayTutorials("AfterCityEntered")
        }

        if(!UnCivGame.Current.settings.tutorialsShown.contains("EnemyCityNeedsConqueringWithMeleeUnit")) {
            for (enemyCity in cloneCivilization.diplomacy.values.filter { it.diplomaticStatus == DiplomaticStatus.War }
                    .map { it.otherCiv() }.flatMap { it.cities }) {
                if (enemyCity.health == 1 && enemyCity.getCenterTile().getTilesInDistance(2)
                                .any { it.getUnits().any { unit -> unit.civInfo == cloneCivilization } })
                    displayTutorials("EnemyCityNeedsConqueringWithMeleeUnit")
            }
        }

        if(gameClone.getCurrentPlayerCivilization().getCivUnits().any { it.health<100 })
            displayTutorials("InjuredUnits")

        if(gameClone.getCurrentPlayerCivilization().getCivUnits().any { it.name=="Worker" })
            displayTutorials("WorkerTrained")

        updateTechButton(cloneCivilization)
        updateDiplomacyButton(cloneCivilization)

        bottomBar.update(tileMapHolder.selectedTile) // has to come before tilemapholder update because the tilemapholder actions depend on the selected unit!
        minimapWrapper.update(cloneCivilization)
        minimapWrapper.y = bottomBar.height // couldn't be bothered to create a separate val for minimap wrapper

        unitActionsTable.update(bottomBar.unitTable.selectedUnit)
        unitActionsTable.y = bottomBar.height

        // if we use the clone, then when we update viewable tiles
        // it doesn't update the explored tiles of the civ... need to think about that harder
        // it causes a bug when we move a unit to an unexplored tile (for instance a cavalry unit which can move far)
        tileMapHolder.updateTiles(currentPlayerCiv)

        topBar.update(cloneCivilization)
        notificationsScroll.update(currentPlayerCiv.notifications)
        notificationsScroll.width = stage.width/3
        notificationsScroll.setPosition(stage.width - notificationsScroll.width - 5f,
                nextTurnButton.y - notificationsScroll.height - 5f)

        if(!gameInfo.oneMoreTurnMode && currentPlayerCiv.victoryManager.hasWon()) game.screen = VictoryScreen()
        else if(currentPlayerCiv.policies.freePolicies>0) game.screen = PolicyPickerScreen(currentPlayerCiv)
        else if(currentPlayerCiv.greatPeople.freeGreatPeople>0) game.screen = GreatPersonPickerScreen()

        if(game.screen==this && !tutorials.isTutorialShowing
                && currentPlayerCiv.diplomaticIncidents.any() && !DiplomaticIncidentPopup.isOpen){
            DiplomaticIncidentPopup(this,currentPlayerCiv.diplomaticIncidents.first())
        }
    }

    private fun updateDiplomacyButton(civInfo: CivilizationInfo) {
        diplomacyButtonWrapper.clear()
        if(civInfo.diplomacy.values.map { it.otherCiv() }
                        .filterNot { it.isDefeated() || it.isPlayerCivilization() || it.isBarbarianCivilization() }
                        .any()) {
            displayTutorials("OtherCivEncountered")
            val btn = TextButton("Diplomacy".tr(), skin)
            btn.onClick { UnCivGame.Current.screen = DiplomacyScreen() }
            diplomacyButtonWrapper.add(btn)
        }
        diplomacyButtonWrapper.pack()
        diplomacyButtonWrapper.y = techButton.y -20 - diplomacyButtonWrapper.height
    }

    private fun updateTechButton(civInfo: CivilizationInfo) {
        techButton.isVisible = civInfo.cities.isNotEmpty()

        techButton.clearChildren()

        if (civInfo.tech.currentTechnology() == null) {
            val buttonPic = Table().apply { background = ImageGetter.getDrawable("OtherIcons/civTableBackground.png").tint(colorFromRGB(7, 46, 43)); defaults().pad(10f) }
            buttonPic.add(Label("{Pick a tech}!".tr(), skin).setFontColor(Color.WHITE).setFontSize(22))
            techButton.add(buttonPic)
        }
        else {
            val currentTech = civInfo.tech.currentTechnology()!!
            val innerButton = TechButton(currentTech,civInfo.tech)
            innerButton.color = colorFromRGB(7, 46, 43)
            techButton.add(innerButton)
            val turnsToTech = civInfo.tech.turnsToTech(currentTech)
            innerButton.text.setText(currentTech.tr() + "\r\n" + turnsToTech
                    + (if(turnsToTech>1) " {turns}".tr() else " {turn}".tr()))
        }

        techButton.setSize(techButton.prefWidth, techButton.prefHeight)
        techButton.setPosition(10f, topBar.y - techButton.height - 5f)
    }

    private fun createNextTurnButton(): TextButton {
        val nextTurnButton = TextButton("Next turn".tr(), CameraStageBaseScreen.skin)
        nextTurnButton.onClick {
            if (currentPlayerCiv.tech.freeTechs != 0) {
                game.screen = TechPickerScreen(true, currentPlayerCiv)
                return@onClick
            } else if (currentPlayerCiv.policies.shouldOpenPolicyPicker) {
                game.screen = PolicyPickerScreen(currentPlayerCiv)
                currentPlayerCiv.policies.shouldOpenPolicyPicker = false
                return@onClick
            }
            else if (currentPlayerCiv.tech.currentTechnology() == null && currentPlayerCiv.cities.isNotEmpty()) {
                game.screen = TechPickerScreen(currentPlayerCiv)
                return@onClick
            }

            bottomBar.unitTable.currentlyExecutingAction = null

            Gdx.input.inputProcessor = null // remove input processing - nothing will be clicked!
            nextTurnButton.disable()
            nextTurnButton.setText("Working...".tr())

            kotlin.concurrent.thread {
                try {
                    gameInfo.nextTurn()
                }
                catch (ex:Exception){
                    game.settings.hasCrashedRecently=true
                    game.settings.save()
                    throw ex
                }

                val gameInfoClone = gameInfo.clone()
                kotlin.concurrent.thread {
                    // the save takes a long time( up to a second!) and we can do it while the player continues his game.
                    // On the other hand if we alter the game data while it's being serialized we could get a concurrent modification exception.
                    // So what we do is we clone all the game data and serialize the clone.
                    GameSaver().saveGame(gameInfoClone, "Autosave")
                    nextTurnButton.enable() // only enable the user to next turn once we've saved the current one
                }

                // If we put this BEFORE the save game, then we try to save the game...
                // but the main thread does other stuff, including showing tutorials which guess what? Changes the game data
                // BOOM! Exception!
                // That's why this needs to be after the game is saved.
                shouldUpdate=true

                nextTurnButton.setText("Next turn".tr())
                Gdx.input.inputProcessor = stage
            }
        }

        return nextTurnButton
    }

    override fun resize(width: Int, height: Int) {

        if (stage.viewport.screenWidth != width || stage.viewport.screenHeight != height) {
            super.resize(width, height)
            game.worldScreen = WorldScreen() // start over.
            game.setWorldScreen()
        }
    }

    var shouldUpdate=true
    override fun render(delta: Float) {
        if(shouldUpdate){ //  This is so that updates happen in the MAIN THREAD, where there is a GL Context,
            if(currentPlayerCiv!=gameInfo.getCurrentPlayerCivilization()){
                UnCivGame.Current.screen = PlayerReadyScreen(gameInfo.getCurrentPlayerCivilization())
                return
            }

            // otherwise images will not load properly!
            update()

            val shownTutorials = UnCivGame.Current.settings.tutorialsShown
            displayTutorials("NextTurn")
            if("BarbarianEncountered" !in shownTutorials
                    && currentPlayerCiv.viewableTiles.any { it.getUnits().any { unit -> unit.civInfo.isBarbarianCivilization() } })
                displayTutorials("BarbarianEncountered")
            if(currentPlayerCiv.cities.size > 2) displayTutorials("SecondCity")
            if(currentPlayerCiv.happiness < 0) displayTutorials("Unhappiness")
            if(currentPlayerCiv.goldenAges.isGoldenAge()) displayTutorials("GoldenAge")
            if(gameInfo.turns >= 100) displayTutorials("ContactMe")
            val resources = currentPlayerCiv.getCivResources()
            if(resources.keys.any { it.resourceType==ResourceType.Luxury }) displayTutorials("LuxuryResource")
            if(resources.keys.any { it.resourceType==ResourceType.Strategic}) displayTutorials("StrategicResource")
            if("EnemyCity" !in shownTutorials
                    && currentPlayerCiv.exploredTiles.asSequence().map { gameInfo.tileMap[it] }
                            .any { it.isCityCenter() && it.getOwner()!=currentPlayerCiv })
                displayTutorials("EnemyCity")
            if("Enables construction of Spaceship parts" in currentPlayerCiv.getBuildingUniques())
                displayTutorials("ApolloProgram")
            if(currentPlayerCiv.getCivUnits().any { it.type == UnitType.Siege })
                displayTutorials("SiegeUnitTrained")
            if(currentPlayerCiv.tech.getUniques().contains("Enables embarkation for land units"))
                displayTutorials("CanEmbark")

            shouldUpdate=false
        }
        super.render(delta)
    }

}

class DiplomaticIncidentPopup(val worldScreen: WorldScreen, val diplomaticIncident: DiplomaticIncident):PopupTable(worldScreen){
    fun getCloseButton(text:String): TextButton {
        val button = TextButton(text.tr(), skin)
        button.onClick { close() }
        return button
    }

    init {
        val otherCiv = worldScreen.gameInfo.getCivilization(diplomaticIncident.civName)
        val otherCivLeaderName = otherCiv.getNation().leaderName+" of "+otherCiv.civName
        add(Label(otherCivLeaderName,skin))
        addSeparator()

        when(diplomaticIncident.type){
            DiplomaticIncidentType.WarDeclaration -> {
                addGoodSizedLabel(otherCiv.getNation().declaringWar).row()
                val responseTable = Table()
                responseTable.add(getCloseButton("You'll pay for this!"))
                responseTable.add(getCloseButton("Very well."))
                add(responseTable)
            }
            DiplomaticIncidentType.Defeated -> {
                addGoodSizedLabel(otherCiv.getNation().defeated).row()
                add(getCloseButton("Farewell."))
            }
            DiplomaticIncidentType.FirstContact -> {
                addGoodSizedLabel(otherCiv.getNation().introduction).row()
                add(getCloseButton("A pleasure to meet you."))
            }
        }
        open()
        isOpen = true
    }

    fun close(){
        worldScreen.currentPlayerCiv.diplomaticIncidents.remove(diplomaticIncident)
        isOpen = false
        remove()
    }

    companion object {
        var isOpen = false
    }
}