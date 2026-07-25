# Fix Multiplayer Player List Scrollability and Year Picker Accessibility

> **For Hermes:** Execute the task checklist below. Commit after each task.

**Goal:** Fix two GUI issues: (1) player list in local multiplayer setup is not scrollable — 4+ players can't be seen; (2) year datepicker (NumberPicker) in multiplayer guess screen is cramped and should be replaced with an accessible EditText.

**Architecture:** 
- Replace the `TextView` player list in `MultiplayerSetupFragment` with a `RecyclerView` + adapter (reusing `item_player_row.xml`).
- Replace `NumberPicker` in `item_player_guess.xml` and `MultiplayerGuessAdapter` with an `EditText` — same pattern the single-player mode already uses.

**Tech Stack:** Kotlin, Android View Binding, RecyclerView, ConstraintLayout

---

### Task 1: Add RecyclerView to fragment_multiplayer_setup.xml

**Objective:** Replace the `textViewPlayerList` TextView with a scrollable RecyclerView.

**Files:**
- Modify: `app/src/main/res/layout/fragment_multiplayer_setup.xml`

**Step 1: Replace the player list TextView with RecyclerView**

Open `fragment_multiplayer_setup.xml`. Replace the `TextView` (lines 41-54):

```xml
    <TextView
        android:id="@+id/textViewPlayerList"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="4dp"
        android:padding="12dp"
        android:background="@drawable/input_background"
        android:textColor="@color/body_text"
        android:textSize="14sp"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/textViewPlayersLabel"
        app:layout_constraintBottom_toTopOf="@+id/buttonStartGame"
        tools:text="Spiller1 — 0 poeng\nSpiller2 — 0 poeng" />
```

with:

```xml
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recyclerViewPlayerList"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="4dp"
        android:overScrollMode="never"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/textViewPlayersLabel"
        app:layout_constraintBottom_toTopOf="@+id/buttonStartGame"
        tools:listitem="@layout/item_player_row" />
```

---

### Task 2: Create PlayerSetupAdapter

**Objective:** Create a simple RecyclerView adapter that binds `item_player_row.xml` for the setup screen.

**Files:**
- Create: `app/src/main/java/com/turbolego/songguesser/PlayerSetupAdapter.kt`

**Step 1: Write the adapter**

```kotlin
package com.turbolego.songguesser

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.turbolego.songguesser.databinding.ItemPlayerRowBinding

class PlayerSetupAdapter(
    private var players: List<MultiPlayerManager.Player>,
    private val onRemoveClick: (String) -> Unit,
) : RecyclerView.Adapter<PlayerSetupAdapter.ViewHolder>() {

    fun updatePlayers(newPlayers: List<MultiPlayerManager.Player>) {
        players = newPlayers
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = players.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlayerRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(players[position])
    }

    inner class ViewHolder(private val binding: ItemPlayerRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(player: MultiPlayerManager.Player) {
            binding.textViewPlayerName.text = "${adapterPosition + 1}. ${player.name}"
            binding.buttonRemovePlayer.setOnClickListener {
                onPlayerRemoveClick(player.name)
            }
            binding.textViewPlayerScore.visibility = android.view.View.GONE
        }
    }
}
```

Wait — note: `item_player_row.xml` has `buttonRemovePlayer` not `buttonViewPlayer` and `textViewPlayerScore` not `textViewPlayerScore`. Let me re-check:

The XML uses: `textViewPlayerName`, `textViewPlayerScore`, `buttonRemovePlayer`. So the adapter should use:

```kotlin
package com.turbolego.songguesser

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.turbolego.songguesser.databinding.ItemPlayerRowBinding

class PlayerSetupAdapter(
    private var players: List<MultiPlayerManager.Player>,
    private val onRemoveClick: (String) -> Unit,
) : RecyclerView.Adapter<PlayerSetupAdapter.ViewHolder>() {

    override fun getItemCount(): Int = players.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlayerRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(players[position])
    }

    fun submitList(newPlayers: List<MultiPlayerManager.Player>) {
        players = newPlayers
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemPlayerRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(player: MultiPlayerManager.Player) {
            binding.textViewPlayerName.text = "${bindingAdapterPosition + 1}. ${player.name}"
            binding.textViewPlayerScore.visibility = android.view.View.GONE
            binding.buttonRemovePlayer.setOnClickListener {
                onRemoveClick(player.name)
            }
        }
    }
}
```

### Task 3: Update MultiplayerSetupFragment to use the RecyclerView

**Objective:** Wire the new RecyclerView with the PlayerSetupAdapter, removing old TextView logic.

**Files:**
- Modify: `app/src/main/java/com/turbolego/songguesser/MultiplayerSetupFragment.kt`

**Step 1: Replace refreshPlayerList and field**

The current code uses `binding.textViewPlayerList` with a StringBuilder and long-press dialog. Replace with a RecyclerView + adapter approach.

**Changes:**
1. Add a field: `private var playerAdapter: PlayerSetupAdapter? = null`
2. In `onViewCreated`, initialize the RecyclerView and adapter
3. Replace `refreshPlayerList()` to update the adapter
4. Remove the long-press dialog (the X button handles removal now)

Full updated `onViewCreated` and `refreshPlayerList`:

```kotlin
    private var playerAdapter: PlayerSetupAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        MultiPlayerManager.clear()

        playerAdapter = PlayerSetupAdapter(
            players = MultiPlayerManager.allPlayers,
            onRemoveClick = { name ->
                MultiPlayerManager.removePlayer(name)
                refreshPlayerList()
            }
        )
        binding.recyclerViewPlayerList.apply {
            adapter = playerAdapter
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        }

        binding.buttonAddPlayer.setOnClickListener {
            showAddPlayerDialog()
        }

        binding.buttonStartGame.setOnClickListener {
            if (MultiPlayerManager.playerCount < 2) {
                Toast.makeText(requireContext(), R.string.min_players, Toast.LENGTH_SHORT).show()
            } else {
                val players = MultiPlayerManager.allPlayers
                val activity = requireActivity() as? MainActivity
                activity?.startMultiplayerGame(players)
            }
        }

        refreshPlayerList()
    }

    private fun refreshPlayerList() {
        val players = MultiPlayerManager.allPlayers
        playerAdapter?.submitList(players)
        binding.buttonStartGame.isEnabled = players.size >= 2

        // Show placeholder if empty
        if (players.isEmpty()) {
            // Optional: could show a "Ingen spillere enda" empty state
        }
    }
```

Note: The fragment no longer needs `textViewPlayerList` binding at all. Remove the old long-press logic from `refreshPlayerList`.

---

### Task 4: Replace NumberPicker with EditText in multiplayer guess UI

**Objective:** Replace the `NumberPicker` in the multiplayer player guess row with an `EditText` for year input — matching the single-player mode's accessible input.

**Files:**
- Modify: `app/src/main/res/layout/item_player_guess.xml`
- Modify: `app/src/main/java/com/turbolego/songguesser/MultiplayerGuessAdapter.kt`

**Step 1: Change item_player_guess.xml**

Replace the `NumberPicker` (lines 23-31) with an `EditText`:

Old:
```xml
    <NumberPicker
        android:id="@+id/numberPickerYear"
        android:layout_width="match_parent"
        android:layout_height="50dp"
        android:layout_marginTop="2dp"
        android:descendantFocusability="blocksDescendants"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/textViewPlayerName" />
```

Replace with:

```xml
    <EditText
        android:id="@+id/editTextYear"
        android:layout_width="120dp"
        android:layout_height="48dp"
        android:layout_marginTop="4dp"
        android:background="@drawable/input_background"
        android:gravity="center"
        android:hint="År"
        android:imeOptions="actionDone"
        android:importantForAutofill="no"
        android:inputType="number"
        android:maxLength="4"
        android:textColor="@color/body_text"
        android:textColorHint="@color/muted_text"
        android:textSize="16sp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/textViewPlayerName" />
```

Also update the result label's constraint from `numberPickerYear` to `editTextYear`:

```xml
    app:layout_constraintTop_toBottomOf="@+id/editTextYear"
```

**Step 2: Update MultiplayerGuessAdapter.kt**

Replace all `NumberPicker` references. The adapter currently tracks values via `IntArray currentValues` and `NumberPicker.setOnValueChangedListener`. Change to edit text behavior:

```kotlin
package com.turbolego.songguesser

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.turbolego.songguesser.databinding.ItemPlayerGuessBinding

class MultiplayerGuessAdapter(
    private val playerNames: List<String>
) : RecyclerView.Adapter<MultiplayerGuessAdapter.PlayerViewHolder>() {

    /** Mirrors each player's current edit text value, updated on every change. */
    private val currentValues = IntArray(playerNames.size) { 1992 }
    private val results = arrayOfNulls<String>(playerNames.size)
    private var gameOver = false

    override fun getItemCount(): Int = playerNames.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val binding = ItemPlayerGuessBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PlayerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        holder.bind(position)
    }

    fun getCurrentPickerValues(): List<Pair<String, Int>> {
        return playerNames.indices.map { i ->
            playerNames[i] to currentValues[i]
        }
    }

    fun revealAnswers() {
        gameOver = true
        notifyDataSetChanged()
    }

    fun setPlayerResult(position: Int, resultText: String) {
        results[position] = resultText
        notifyItemChanged(position)
    }

    fun resetForNewRound() {
        results.fill(null)
        gameOver = false
        notifyDataSetChanged()
    }

    inner class PlayerViewHolder(private val binding: ItemPlayerGuessBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(position: Int) {
            val playerName = playerNames[position]
            binding.textViewPlayerName.text = playerName

            val editYear = binding.editTextYear

            // Set current value (show only if non-zero)
            val currentVal = currentValues[position]
            editYear.setText(if (currentVal > 0) currentVal.toString() else "")

            editYear.isEnabled = !gameOver

            // Update mirror when text changes
            editYear.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString() ?: ""
                    val year = text.toIntOrNull()
                    if (year != null && year in 1960..2025) {
                        currentValues[position] = year
                    } else if (text.isEmpty()) {
                        currentValues[position] = 0
                    }
                }
            })

            // Show/hide result
            val resultText = results[position]
            if (resultText != null) {
                binding.textViewPlayerResult.visibility = View.VISIBLE
                binding.textViewPlayerResult.text = resultText
            } else {
                binding.textViewPlayerResult.visibility = View.GONE
            }
        }
    }
}
```

Note: Since we replace `binding.numberPickerYear` with `binding.editTextYear`, the generated binding class `ItemPlayerGuessBinding` needs the view ID `editTextYear` in the XML. Android's view binding uses the `android:id` attribute to generate the field name, so renaming from `@+id/numberPickerYear` to `@+id/editTextYear` will produce `binding.editTextYear` automatically.

---

### Task 5: Verify - Run the Gradle build

**Objective:** Build the APK to verify everything compiles.

**Step 1: Run the build**

```bash
cd /tmp/GuessTheSongYear && ./gradlew assembleDebug --no-daemon 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL

---

### Task 6: Commit

**Step 1: Commit all changes**

```bash
cd /tmp/GuessTheSongYear
git add -A
git commit -m "fix: scrollable player list + replace NumberPicker with EditText

- Replace non-scrollable TextView with RecyclerView in MultiplayerSetupFragment
- Add PlayerSetupAdapter for the setup player list using item_player_row.xml
- Replace cramped NumberPicker with accessible EditText in item_player_guess.xml
- Update MultiplayerGuessAdapter to use EditText instead of NumberPicker"
```