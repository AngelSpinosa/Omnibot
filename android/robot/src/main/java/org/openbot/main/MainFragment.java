package org.openbot.main;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import org.openbot.R;
import org.openbot.common.FeatureList;
import org.openbot.databinding.FragmentMainBinding;
import org.openbot.model.SubCategory;
import org.openbot.original.DefaultActivity;
import timber.log.Timber;

public class MainFragment extends Fragment implements OnItemClickListener<SubCategory> {

  private MainViewModel mViewModel;
  private FragmentMainBinding binding;

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    binding = FragmentMainBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    mViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

    //Ordenar el grid para que el titulo utilice el espacio de dos celdas
    int spanCount = 2; // El número de columnas
    GridLayoutManager gridLayoutManager = new GridLayoutManager(requireContext(), spanCount);

    gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
      @Override
      public int getSpanSize(int position) {
        // Si es el primer item (posición 0), haz que ocupe las 2 columnas (spanCount).
        // Si no, que ocupe solo 1.
        return (position == 0) ? spanCount : 1;
      }
    });

    binding.list.setLayoutManager(gridLayoutManager);

    //Llenamos nuestra lista de elementos, estos declarados en la clase "FeactureList"
    binding.list.setAdapter(new CategoryAdapter(FeatureList.getCategories(), this));
  }

  //Dependiendo lo que seleecciona, se pasa a la siguiente actividad
  @Override
  public void onItemClick(SubCategory subCategory) {

    Timber.d("onItemClick: %s", subCategory.getTitle());

    switch (subCategory.getTitle()) {
      case FeatureList.PROJECTS:
        Navigation.findNavController(requireView()).navigate(R.id.projectsFragment);
        break;
      case FeatureList.FREE_ROAM:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_freeRoamFragment);
        break;

      case FeatureList.DATA_COLLECTION:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_loggerFragment);
        break;

      case FeatureList.CONTROLLER:
        // For a library module, uncomment the following line
        // intent = new Intent(this, ControllerActivity.class);
        // startActivity(intent);
        break;

      case FeatureList.CONTROLLER_MAPPING:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_controllerMappingFragment);
        break;

      case FeatureList.ROBOT_INFO:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_robotInfoFragment);
        break;

      case FeatureList.AUTOPILOT:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_autopilotFragment);
        break;

      case FeatureList.OBJECT_NAV:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_objectNavFragment);
        break;

      case FeatureList.POINT_GOAL_NAVIGATION:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_pointGoalNavigationFragment);
        break;

      case FeatureList.MODEL_MANAGEMENT:
        Navigation.findNavController(requireView())
            .navigate(R.id.action_mainFragment_to_modelManagementFragment);
        break;

      case FeatureList.DEFAULT:
        Intent intent = new Intent(requireActivity(), DefaultActivity.class);
        startActivity(intent);
        break;

      case FeatureList.INTERPRETATION_IMAGES:
        intent = new Intent(requireActivity(), InterpretationActivity.class);
        startActivity(intent);
        break;
    }
  }
}
