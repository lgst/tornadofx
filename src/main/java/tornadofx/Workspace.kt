package tornadofx

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleIntegerProperty
import javafx.collections.FXCollections
import javafx.event.EventHandler
import javafx.geometry.HorizontalDirection
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ToolBar
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.KeyEvent.KEY_PRESSED
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import kotlin.reflect.KClass

class HeadingContainer : HBox() {
    init {
        addClass("heading-container")
    }
}

class WorkspaceArea : BorderPane() {
    internal var dynamicComponentMode: Boolean = false
    internal val dynamicComponents = FXCollections.observableArrayList<Node>()
    var header: ToolBar by singleAssign()

    init {
        addClass("workspace")
    }
}

open class Workspace(title: String = "Workspace") : View(title) {
    var refreshButton: Button by singleAssign()
    var saveButton: Button by singleAssign()

    private val viewStack = FXCollections.observableArrayList<UIComponent>()
    private val viewPos = SimpleIntegerProperty(-1)
    val maxViewStackDepthProperty = SimpleIntegerProperty(10)
    var maxViewStackDepth by maxViewStackDepthProperty
    private val viewStackDisabled = booleanBinding(maxViewStackDepthProperty) { value == 0 }

    val headingContainer = HeadingContainer()
    private val realDockedComponentProperty = ReadOnlyObjectWrapper<UIComponent>()
    val dockedComponentProperty: ReadOnlyObjectProperty<UIComponent> = realDockedComponentProperty.readOnlyProperty
    val dockedComponent: UIComponent? get() = realDockedComponentProperty.value

    val leftDrawer: Drawer get() {
        if (root.left is Drawer) return root.left as Drawer
        root.left = Drawer(HorizontalDirection.LEFT, false)
        return root.left as Drawer
    }

    val rightDrawer: Drawer get() {
        if (root.right is Drawer) return root.right as Drawer
        root.right = Drawer(HorizontalDirection.RIGHT, false)
        return root.right as Drawer
    }

    companion object {
        val activeWorkspaces = FXCollections.observableArrayList<Workspace>()

        fun closeAll() {
            activeWorkspaces.forEach(Workspace::closeModal)
        }

        init {
            FX.log.warning("The Workspace feature is experimental and subject to change even in minor releases!")
            importStylesheet("/tornadofx/workspace.css")
        }
    }

    fun disableNavigation() {
        viewStack.clear()
        maxViewStackDepth = 0
    }

    private val shortcutProxy: EventHandler<KeyEvent> = EventHandler { event ->
        dockedComponent?.apply {
            val match = accelerators.keys.asSequence().find { it.match(event) }
            if (match != null) {
                accelerators[match]?.invoke()
                event.consume()
            } else if (event.isControlDown && event.code == KeyCode.S) {
                if (!saveButton.isDisable) {
                    onSave()
                    event.consume()
                }
            } else if (event.code == KeyCode.F5 || (event.isControlDown && event.code == KeyCode.R)) {
                if (!refreshButton.isDisable) {
                    onRefresh()
                    event.consume()
                }
            }
        }
        if (!event.isConsumed) {
            // Fallback to accelerators registered on the Workspace
            val localMatch = accelerators.keys.asSequence().find { it.match(event) }
            if (localMatch != null) {
                accelerators[localMatch]?.invoke()
                event.consume()
            }
        }
    }

    override fun onDock() {
        root.scene?.addEventFilter(KEY_PRESSED, shortcutProxy)
        activeWorkspaces.add(this)
    }

    override fun onUndock() {
        root.scene?.removeEventFilter(KEY_PRESSED, shortcutProxy)
        activeWorkspaces.remove(this)
    }

    override val root = WorkspaceArea().apply {
        top {
            vbox {
                header = toolbar {
                    addClass("header")
                    // Force the container to retain pos center even when it's resized (hack to make ToolBar behave)
                    skinProperty().onChange {
                        (lookup(".container") as? HBox)?.apply {
                            alignment = Pos.CENTER_LEFT
                            alignmentProperty().onChange {
                                if (it != Pos.CENTER_LEFT)
                                    alignment = Pos.CENTER_LEFT
                            }
                        }
                    }
                    button {
                        addClass("icon-only")
                        graphic = label { addClass("icon", "back") }
                        setOnAction {
                            viewPos.set(viewPos.get() - 1)
                            dock(viewStack[viewPos.get()], false)
                        }
                        disableProperty().bind(booleanBinding(viewPos, viewStack) { value < 1 })
                        removeWhen { viewStackDisabled }
                    }
                    button {
                        addClass("icon-only")
                        graphic = label { addClass("icon", "forward") }
                        setOnAction {
                            viewPos.set(viewPos.get() + 1)
                            dock(viewStack[viewPos.get()], false)
                        }
                        disableProperty().bind(booleanBinding(viewPos, viewStack) { value == viewStack.size - 1 })
                        removeWhen { viewStackDisabled }
                    }
                    button {
                        addClass("icon-only")
                        refreshButton = this
                        isDisable = true
                        graphic = label {
                            addClass("icon", "refresh")
                        }
                        setOnAction {
                            onRefresh()
                        }
                    }
                    button {
                        addClass("icon-only")
                        saveButton = this
                        isDisable = true
                        graphic = label { addClass("icon", "save") }
                        setOnAction {
                            onSave()
                        }
                    }
                    add(headingContainer)
                    spacer()
                }
            }
        }
    }

    override fun onSave() {
        if (dockedComponentProperty.value?.savable?.value ?: false)
            dockedComponentProperty.value?.onSave()
    }

    override fun onRefresh() {
        if (dockedComponentProperty.value?.refreshable?.value ?: false)
            dockedComponentProperty.value?.onRefresh()
    }

    inline fun <reified T : UIComponent> dock(scope: Scope = this@Workspace.scope, params: Map<*, Any?>? = null) = dock(find(T::class, scope, params))

    fun dock(child: UIComponent, updateViewStack: Boolean = true) {
        titleProperty.bind(child.titleProperty)
        refreshButton.disableProperty().cleanBind(child.refreshable.not())
        saveButton.disableProperty().cleanBind(child.savable.not())

        headingContainer.children.clear()
        headingContainer.label(child.headingProperty) {
            graphicProperty().bind(child.iconProperty)
        }

        if (updateViewStack && !viewStackDisabled.value && !viewStack.contains(child)) {
            // Remove everything after viewpos
            while (viewPos.get() != (viewStack.size - 1) && viewStack.size > viewPos.get())
                viewStack.removeAt(viewPos.get() + 1)

            // Add to end of stack
            viewStack.add(child)

            // Update index
            viewPos.set(viewStack.indexOf(child))

            // Ensure max stack size
            while (viewStack.size >= maxViewStackDepth) {
                viewStack.removeAt(0)
            }
        }

        // Remove previously added dynamic components
        root.dynamicComponents.forEach(Node::removeFromParent)
        root.dynamicComponents.clear()

        inDynamicComponentMode {
            realDockedComponentProperty.value = child
            root.center = child.root
        }

        // Make sure we are visible
        if (root.scene == null) {
            openWindow()
        }
    }

    private fun inDynamicComponentMode(function: () -> Unit) {
        root.dynamicComponentMode = true
        try {
            function()
        } finally {
            root.dynamicComponentMode = false
        }
    }

    /**
     * Create a new scope and associate it with this Workspace and optionally add one
     * or more Injectable instances into the scope. The op block operates on the workspace and is passed the new scope. The following example
     * creates a new scope, injects a Customer Model into it and docks the CustomerEditor
     * into the Workspace:
     *
     * <pre>
     * workspace.withNewScope(CustomerModel(customer)) { newScope ->
     *     dock<CustomerEditor>(newScope)
     * }
     * </pre>
     */
    fun withNewScope(vararg setInScope: Injectable, op: Workspace.(Scope) -> Unit) {
        val newScope = Scope()
        newScope.workspaceInstance = this
        newScope.set(*setInScope)
        op(this, newScope)
    }

    /**
     * Create a new scope and associate it with this Workspace and dock the given UIComponent type into
     * the scope, passing the given parameters on to the UIComponent and optionally injecting the given Injectables into the new scope.
     */
    inline fun <reified T : UIComponent> dockInNewScope(params: Map<*, Any?>, vararg setInScope: Injectable) {
        withNewScope(*setInScope) {
            dock<T>(scope, params)
        }
    }

    /**
     * Create a new scope and associate it with this Workspace and dock the given UIComponent type into
     * the scope, optionally injecting the given Injectables into the new scope.
     */
    inline fun <reified T : UIComponent> dockInNewScope(vararg setInScope: Injectable) {
        withNewScope(*setInScope) { newScope ->
            dock<T>(newScope)
        }
    }

    /**
     * Create a new scope and associate it with this Workspace and dock the given UIComponent type into
     * the scope and optionally injecting the given Injectables into the new scope.
     */
    fun <T : UIComponent> dockInNewScope(uiComponent: T, vararg setInScope: Injectable) {
        withNewScope(*setInScope) {
            dock(uiComponent)
        }
    }
}

open class WorkspaceApp(val initiallyDockedView: KClass<out UIComponent>, vararg stylesheet: KClass<out Stylesheet>) : App(Workspace::class, *stylesheet) {
    override fun onBeforeShow(view: UIComponent) {
        workspace.dock(find(initiallyDockedView))
    }
}