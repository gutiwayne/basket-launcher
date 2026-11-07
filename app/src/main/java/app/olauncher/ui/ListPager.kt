package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AppList
import app.olauncher.data.AppListCodec
import app.olauncher.data.AppListStore
import app.olauncher.data.AppModel
import app.olauncher.data.ListApp
import app.olauncher.data.Prefs
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.databinding.FragmentAppDrawerBinding
import app.olauncher.helper.OlDialog
import app.olauncher.helper.createDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showToast
import kotlin.math.abs

/**
 * Basket Launcher: pages in the app drawer. Page 0 is "All apps"; each user list is one more page.
 * - Swipe left/right anywhere in the drawer to change page (wraps around).
 * - The page name sits above the search box, with ‹ › arrows on its right and dots for the position;
 *   long press the name to manage lists.
 * - Long press an app → "Lists" to add it to or remove it from any list. Removing a list's last app
 *   (or uninstalling it) deletes the list; a list created empty stays until it gets its first app.
 * Search works the same on every page, limited to that page's apps.
 */
class ListPager(
    private val binding: FragmentAppDrawerBinding,
    private val prefs: Prefs,
    initialStep: Int,
    private val itemAt: (position: Int) -> AppModel?,
    private val onPageChanged: () -> Unit,
) {
    private val context: Context = binding.root.context
    private val store = AppListStore(context)
    private var lists: List<AppList> = store.all()

    /** 0 = All apps, n = lists[n - 1]. */
    private var page = when {
        lists.isEmpty() -> 0
        initialStep > 0 -> 1
        initialStep < 0 -> lists.size
        else -> 0
    }

    private val title = TextView(context)
    private val previous = TextView(context)
    private val next = TextView(context)
    private val dots = TextView(context)

    init {
        setUpHeader()
        setUpSwipe()
        setUpRowAction()
        render()
    }

    /** Apps for the current page. Page 0 is untouched (private space header included). */
    fun filter(all: List<AppModel>): MutableList<AppModel> {
        val list = currentList() ?: return all.toMutableList()
        return all.filter { model -> toListApp(model)?.let { app -> list.apps.any { it.matches(app.appPackage, app.user, app.shortcutId) } } == true }
            .toMutableList()
    }

    private fun currentList(): AppList? = lists.getOrNull(page - 1)

    private fun pageName(): String = currentList()?.name ?: context.getString(R.string.basket_all_apps)

    private fun reload(keepListId: String? = currentList()?.id) {
        lists = store.all()
        page = if (keepListId == null) 0 else lists.indexOfFirst { it.id == keepListId } + 1
        render()
        onPageChanged()
    }

    private fun goTo(newPage: Int) {
        val count = lists.size + 1
        page = ((newPage % count) + count) % count
        binding.search.setQuery("", false)
        binding.recyclerView.scrollToPosition(0)
        render()
        onPageChanged()
        if (currentList()?.apps?.isEmpty() == true) context.showToast(R.string.basket_list_empty)
    }

    private fun render() {
        title.text = pageName()
        dots.isVisible = lists.isNotEmpty()
        previous.isVisible = lists.isNotEmpty()
        next.isVisible = lists.isNotEmpty()
        dots.text = (0..lists.size).joinToString("  ") { if (it == page) "●" else "○" }
    }

    // --- Header: page name above the search box with ‹ › on its right, dots below the list ---

    private fun setUpHeader() {
        val root = binding.root as LinearLayout
        val searchRow = binding.search.parent as View
        title.apply {
            setTextAppearance(R.style.TextSmall)
            alpha = 0.6f
            gravity = prefs.appLabelAlignment or android.view.Gravity.CENTER_VERTICAL
            setPadding(24.dpToPx(), 0, 8.dpToPx(), 0)
            setOnLongClickListener { showManageDialog(); true }
        }
        val selectable = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }.resourceId
        listOf(previous to "‹", next to "›").forEach { (arrow, glyph) ->
            arrow.apply {
                text = glyph
                setTextAppearance(R.style.TextLarge)
                alpha = 0.35f // discreet, like the dots
                gravity = android.view.Gravity.CENTER
                minWidth = 40.dpToPx()  // comfortable touch target for a small glyph
                minHeight = 40.dpToPx()
                includeFontPadding = false
                setBackgroundResource(selectable)
                contentDescription = context.getString(if (arrow === previous) R.string.basket_previous_list else R.string.basket_next_list)
            }
        }
        previous.setOnClickListener { goTo(page - 1) }
        next.setOnClickListener { goTo(page + 1) }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 16.dpToPx(), 0)
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(previous, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(header, root.indexOfChild(searchRow), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dots.apply {
            setTextAppearance(R.style.TextSmall)
            alpha = 0.5f
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 12.dpToPx())
        }
        root.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // --- Horizontal swipe: finger to the left = next page, to the right = previous ---

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpSwipe() {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) < SWIPE_MIN_DISTANCE_DP.dpToPx() || abs(dx) < abs(dy) * 1.5f || abs(velocityX) < SWIPE_MIN_VELOCITY) return false
                goTo(if (dx < 0) page + 1 else page - 1)
                return true
            }
        })
        // Over the app list: taps and vertical scroll pass through, but once a drag is clearly horizontal
        // the list takes it over, so the app under the finger gets ACTION_CANCEL and is not launched.
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        binding.recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            private var downX = 0f
            private var downY = 0f

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        if (abs(dx) > touchSlop * 2 && abs(dx) > abs(dy) * 1.5f) {
                            rv.parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (e.actionMasked != MotionEvent.ACTION_UP) return
                val dx = e.x - downX
                if (abs(dx) >= SWIPE_MIN_DISTANCE_DP.dpToPx()) goTo(if (dx < 0) page + 1 else page - 1)
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
        // Over the empty parts of the drawer.
        binding.root.setOnTouchListener { _, e -> detector.onTouchEvent(e) }
    }

    // --- "Lists" button in the long-press row of every app ---

    private fun setUpRowAction() {
        binding.recyclerView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val row = view.findViewById<LinearLayout>(R.id.appHideLayout) ?: return
                if (row.findViewWithTag<View>(ROW_ACTION_TAG) != null) return
                val info = row.findViewById<TextView>(R.id.appInfo) ?: return
                val action = TextView(context).apply {
                    tag = ROW_ACTION_TAG
                    text = context.getString(R.string.basket_lists)
                    setTextColor(info.textColors)
                    typeface = info.typeface
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, info.textSize)
                    gravity = android.view.Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                    compoundDrawablePadding = 2.dpToPx()
                    setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_basket_list, 0, 0)
                    setOnClickListener {
                        val model = itemAt(binding.recyclerView.getChildAdapterPosition(view)) ?: return@setOnClickListener
                        row.findViewById<View>(R.id.appMenuClose)?.performClick()
                        toListApp(model)?.let { showListsFor(it) }
                    }
                }
                val closeIndex = row.indexOfChild(row.findViewById(R.id.appMenuClose))
                row.addView(action, if (closeIndex >= 0) closeIndex else row.childCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    private fun toListApp(model: AppModel): ListApp? = when (model) {
        is AppModel.App -> ListApp(model.appLabel, model.appPackage, model.activityClassName, model.user.toString())
        is AppModel.PinnedShortcut -> ListApp(model.appLabel, model.appPackage, null, model.user.toString(), model.shortcutId)
        is AppModel.PrivateSpaceHeader -> null
    }

    /** Tick or untick lists for one app; the dialog reopens after each tap so several lists are quick. */
    private fun showListsFor(app: ListApp) {
        val current = store.all()
        val labels = current.map { (if (AppListCodec.contains(current, it.id, app)) "✓  " else "+  ") + it.name } +
            context.getString(R.string.basket_new_list)
        showChoiceDialog(app.label, labels) { index ->
            if (index < current.size) {
                val list = current[index]
                store.update { AppListCodec.toggle(it, list.id, app) }
                reload()
                if (lists.none { it.id == list.id }) context.showToast(context.getString(R.string.basket_list_deleted, list.name))
                showListsFor(app)
            } else askName(context.getString(R.string.basket_list_default_name)) { name ->
                store.update { AppListCodec.create(it, name, app) }
                reload()
                showListsFor(app)
            }
        }
    }

    private fun showManageDialog() {
        val list = currentList()
        val options = mutableListOf<Pair<Int, () -> Unit>>()
        options += R.string.basket_new_list to {
            askName(context.getString(R.string.basket_list_default_name)) { name ->
                store.update { AppListCodec.create(it, name) }
                lists = store.all()
                goTo(lists.size)
            }
        }
        if (list != null) {
            options += R.string.basket_rename_list to {
                askName(list.name) { name ->
                    store.update { AppListCodec.rename(it, list.id, name) }
                    reload(list.id)
                }
            }
            if (page > 1) options += R.string.basket_move_left to {
                store.update { AppListCodec.move(it, list.id, -1) }
                reload(list.id)
            }
            if (page < lists.size) options += R.string.basket_move_right to {
                store.update { AppListCodec.move(it, list.id, 1) }
                reload(list.id)
            }
            options += R.string.basket_delete_list to {
                context.createDialog(
                    title = R.string.basket_delete_list,
                    action = R.string.basket_delete,
                    message = R.string.basket_delete_list_message,
                    onAction = {
                        store.update { AppListCodec.delete(it, list.id) }
                        reload(null)
                    },
                ).showRespectingStatusBar()
            }
        }
        showChoiceDialog(pageName(), options.map { context.getString(it.first) }) { options[it].second() }
    }

    // --- Dialogs in the app's own style ---

    private fun primaryColor(): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(R.attr.primaryColor, value, true)
        return value.data
    }

    private fun askName(initial: String, onName: (String) -> Unit) {
        val input = EditText(context).apply {
            setText(initial)
            setSelection(text.length)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(primaryColor())
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 16.dpToPx(); marginEnd = 8.dpToPx() }
        }
        context.createDialog(
            title = R.string.basket_list_name,
            action = R.string.basket_save,
            onAction = {
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            },
            content = { input },
        ).showRespectingStatusBar()
    }

    private fun showChoiceDialog(titleText: String, labels: List<String>, onPick: (Int) -> Unit) {
        val dialog = OlDialog(context)
        val dialogBinding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        dialogBinding.tvTitle.text = titleText
        dialogBinding.tvAction.isVisible = false
        dialogBinding.ivClose.setOnClickListener { dialog.dismiss() }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 12.dpToPx() }
        }
        val selectable = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        labels.forEachIndexed { index, label ->
            column.addView(TextView(context).apply {
                text = label
                setTextAppearance(R.style.TextLarge)
                setTextColor(primaryColor())
                setPadding(0, 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
                isFocusable = true
                setBackgroundResource(selectable)
                setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialogBinding.contentContainer.addView(column)
        dialogBinding.contentContainer.isVisible = true
        dialog.setView(dialogBinding.root)
        dialog.showRespectingStatusBar()
    }

    companion object {
        /** Navigation argument: +1 opens the drawer on the next list, -1 on the previous one, 0 on All apps. */
        const val ARG_STEP = "basket_list_step"
        private const val ROW_ACTION_TAG = "basket_lists_action"
        private const val SWIPE_MIN_DISTANCE_DP = 64
        private const val SWIPE_MIN_VELOCITY = 300f
    }
}
